package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.seu.studyassistant.R;
import com.seu.studyassistant.engine.AnswerResult;
import com.seu.studyassistant.engine.OpenAiClient;
import com.seu.studyassistant.engine.RetrievalEngine;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.User;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UC5: Ask a Question, and UC9: Receive Related Resources.
 *
 * Renders three outcomes:
 *   - answered  : grounded answer, its source materials, and the Lecture Connection Finder
 *   - declined  : NFR 14.1 / UC5 alternative course 3.a, when coverage is below threshold
 *   - capped    : free tier daily limit reached (Cost Report Section 4)
 */
public class AskQuestionActivity extends BaseActivity {

    private long courseId;
    private EditText etQuestion;
    private View answerBlock, declinedBlock, suggestBlock, loadingBlock, errorBlock;
    private LinearLayout sourcesContainer, relatedContainer, suggestContainer;
    private RetrievalEngine engine;

    private final OpenAiClient ai = new OpenAiClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Kept so the error state's Try again button can re-run the same question. */
    private String lastQuestion;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ask_question);

        courseId = getIntent().getLongExtra(EXTRA_COURSE_ID, -1);
        engine = new RetrievalEngine(db);

        etQuestion = findViewById(R.id.etQuestion);
        answerBlock = findViewById(R.id.answerBlock);
        declinedBlock = findViewById(R.id.declinedBlock);
        suggestBlock = findViewById(R.id.suggestBlock);
        loadingBlock = findViewById(R.id.loadingBlock);
        errorBlock = findViewById(R.id.errorBlock);
        sourcesContainer = findViewById(R.id.sourcesContainer);
        relatedContainer = findViewById(R.id.relatedContainer);
        suggestContainer = findViewById(R.id.suggestContainer);

        Course c = db.courseById(courseId);
        setupHeader(c != null ? c.code : getString(R.string.ask_question), true);

        findViewById(R.id.btnAsk).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ask(); }
        });

        findViewById(R.id.btnAiRetry).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (lastQuestion != null) send(lastQuestion);
            }
        });

        buildSuggestions();
        showRemainingCap();
    }

    /**
     * Seeds the empty state with real starter questions taken from the titles of this
     * course's approved material, so a first-time user always has something that works.
     */
    private void buildSuggestions() {
        suggestContainer.removeAllViews();
        List<Material> approved = db.materials(courseId, true);
        int shown = 0;
        for (Material m : approved) {
            if (shown >= 3) break;
            String topic = m.title.contains(":")
                    ? m.title.substring(m.title.indexOf(':') + 1).trim() : m.title;
            final String question = "What is " + topic.toLowerCase() + "?";

            LinearLayout chip = card(question, getString(m.typeLabelRes()), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    etQuestion.setText(question);
                    etQuestion.setSelection(question.length());
                    ask();
                }
            });
            suggestContainer.addView(chip);
            shown++;
        }
        suggestBlock.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
    }

    private void showRemainingCap() {
        User u = currentUser();
        if (u == null) return;
        TextView cap = findViewById(R.id.tvCap);
        if (!"free".equals(u.tier)) { cap.setVisibility(View.GONE); return; }

        int used = db.questionsToday(u.id);
        int left = Math.max(0, FREE_DAILY_LIMIT - used);
        cap.setVisibility(View.VISIBLE);
        cap.setText(getString(R.string.cap_remaining, left, FREE_DAILY_LIMIT));
    }

    private void ask() {
        User u = currentUser();
        if (u == null) { logout(); return; }

        String question = etQuestion.getText().toString().trim();
        if (question.isEmpty()) {
            toast(getString(R.string.enter_question));
            return;
        }

        // Free tier daily cap.
        if ("free".equals(u.tier) && db.questionsToday(u.id) >= FREE_DAILY_LIMIT) {
            toast(getString(R.string.cap_reached, FREE_DAILY_LIMIT));
            open(BillingActivity.class);
            return;
        }

        send(question);
    }

    /**
     * Runs one question against OpenAI.
     *
     * Retrieval still runs first, but its job has changed: it no longer decides whether the
     * student gets an answer, it only supplies approved course text as context when the course
     * actually covers the question. The Content Lock still holds - loadApprovedPassages only
     * ever returns approved material, so unapproved text is never sent anywhere - but a
     * question the course does not cover is now answered from general knowledge instead of
     * being refused.
     */
    private void send(final String question) {
        final User u = currentUser();
        if (u == null) { logout(); return; }

        lastQuestion = question;

        // Retrieval is cheap and local, so it stays on the main thread; only the network moves.
        final AnswerResult r = engine.ask(courseId, question);
        final boolean grounded = !r.declined;
        final String context = grounded ? r.answer : null;

        suggestBlock.setVisibility(View.GONE);
        showLoading();

        io.execute(new Runnable() {
            @Override public void run() {
                final OpenAiClient.Result result =
                        ai.ask(getApplicationContext(), question, context);

                main.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        loadingBlock.setVisibility(View.GONE);

                        if (!result.isOk()) {
                            renderError(result);
                            return;
                        }

                        db.logQuestion(u.id, courseId, question, true);
                        renderAnswer(result.text, grounded, r);
                        showRemainingCap();
                    }
                });
            }
        });
    }

    private void showLoading() {
        answerBlock.setVisibility(View.GONE);
        declinedBlock.setVisibility(View.GONE);
        errorBlock.setVisibility(View.GONE);
        loadingBlock.setVisibility(View.VISIBLE);
    }

    /** Every failure reaches the screen. Nothing is allowed to fail silently. */
    private void renderError(OpenAiClient.Result result) {
        answerBlock.setVisibility(View.GONE);
        declinedBlock.setVisibility(View.GONE);
        errorBlock.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.tvAiError)).setText(getString(result.messageRes()));
    }

    /**
     * @param grounded whether approved course material actually covered the question; it
     *                 decides the badge and whether sources are worth listing.
     */
    private void renderAnswer(String answer, boolean grounded, AnswerResult r) {
        declinedBlock.setVisibility(View.GONE);
        errorBlock.setVisibility(View.GONE);
        answerBlock.setVisibility(View.VISIBLE);

        ((TextView) findViewById(R.id.tvAnswer)).setText(answer);
        ((TextView) findViewById(R.id.tvGrounded)).setText(getString(grounded
                ? R.string.answered_from_course : R.string.answered_generally));

        TextView coverage = findViewById(R.id.tvCoverage);
        coverage.setVisibility(grounded ? View.VISIBLE : View.GONE);
        if (grounded) coverage.setText(r.coveragePercent() + "%");

        // Sources and the Lecture Connection Finder only make sense for a grounded answer;
        // a general-knowledge answer has no approved material behind it to cite.
        sourcesContainer.removeAllViews();
        sourcesContainer.setVisibility(grounded ? View.VISIBLE : View.GONE);
        if (grounded) {
            for (final Material m : r.sources) {
                sourcesContainer.addView(card(m.title,
                        getString(m.typeLabelRes()) + "  •  " + getString(R.string.approved),
                        openMaterial(m)));
            }
        }

        relatedContainer.removeAllViews();
        boolean any = grounded && !r.related.isEmpty();
        findViewById(R.id.tvRelatedTitle).setVisibility(any ? View.VISIBLE : View.GONE);
        findViewById(R.id.tvRelatedHint).setVisibility(any ? View.VISIBLE : View.GONE);
        if (any) {
            for (final Material m : r.related) {
                relatedContainer.addView(card(m.title, getString(m.typeLabelRes()), openMaterial(m)));
            }
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View.OnClickListener openMaterial(final Material m) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(MaterialViewActivity.class, EXTRA_MATERIAL_ID, m.id);
            }
        };
    }
}
