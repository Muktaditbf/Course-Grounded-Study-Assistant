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

import java.util.ArrayList;
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
     * UC5 / NFR 14.1: answers strictly from approved material.
     *
     * 1. Retrieval ranks the course's approved passages. If they do not cover the question
     *    well enough, the app declines - no AI call, no guess.
     * 2. Otherwise the AI gets only the top passages, numbered, and must answer from them and
     *    cite them. If it finds the answer is not actually there, it says so, and the app
     *    declines then too.
     * 3. With no AI available (no key, offline, quota), the best matching passages are shown
     *    as they are, so a student still gets the teacher's own words with their sources.
     */
    private void send(final String question) {
        final User u = currentUser();
        if (u == null) { logout(); return; }

        lastQuestion = question;
        suggestBlock.setVisibility(View.GONE);
        showLoading();

        io.execute(new Runnable() {
            @Override public void run() {
                final AnswerResult r = engine.ask(courseId, question);
                if (r.declined) {
                    post(() -> {
                        db.logQuestion(u.id, courseId, question, false);
                        renderDeclined(r);
                    });
                    return;
                }

                List<String> titles = new ArrayList<>();
                for (Material m : r.passageSources) titles.add(m.title);
                final OpenAiClient.Result result =
                        ai.askGrounded(getApplicationContext(), question, r.passages, titles, null);

                post(() -> {
                    if (!result.isOk()) {
                        // No AI: the teacher's own passages are still a grounded answer.
                        db.logQuestion(u.id, courseId, question, true);
                        renderAnswer(r.answer, r, r.sources, getString(R.string.answered_from_excerpts));
                        return;
                    }
                    String text = result.text.trim();
                    if (text.startsWith(OpenAiClient.NOT_COVERED)) {
                        db.logQuestion(u.id, courseId, question, false);
                        renderDeclined(r);
                        return;
                    }
                    db.logQuestion(u.id, courseId, question, true);
                    renderAnswer(text, r, citedSources(text, r), getString(R.string.answered_from_course));
                });
            }
        });
    }

    /** Runs on the main thread unless the screen has gone. */
    private void post(final Runnable r) {
        main.post(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                loadingBlock.setVisibility(View.GONE);
                r.run();
                showRemainingCap();
            }
        });
    }

    /** The materials the answer actually cites ([1], [2]...), in first-cited order. */
    static List<Material> citedSources(String answer, AnswerResult r) {
        java.util.LinkedHashSet<Material> out = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(answer);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1)) - 1;
            if (n >= 0 && n < r.passageSources.size()) {
                Material mat = r.passageSources.get(n);
                if (seen.add(mat.id)) out.add(mat);
            }
        }
        // A model that forgot to cite still answered from these passages.
        if (out.isEmpty()) {
            for (Material mat : r.passageSources) if (seen.add(mat.id)) out.add(mat);
        }
        return new ArrayList<>(out);
    }

    private void showLoading() {
        answerBlock.setVisibility(View.GONE);
        declinedBlock.setVisibility(View.GONE);
        errorBlock.setVisibility(View.GONE);
        loadingBlock.setVisibility(View.VISIBLE);
    }

    /** NFR 14.1 / UC5 3.a: the approved material does not cover this, so nothing is guessed. */
    private void renderDeclined(AnswerResult r) {
        answerBlock.setVisibility(View.GONE);
        errorBlock.setVisibility(View.GONE);
        declinedBlock.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.tvDeclinedCoverage)).setText(getString(
                R.string.declined_coverage_format, r.coveragePercent(),
                (int) Math.round(RetrievalEngine.COVERAGE_THRESHOLD * 100)));
    }

    private void renderAnswer(String answer, AnswerResult r, List<Material> sources, String badge) {
        declinedBlock.setVisibility(View.GONE);
        errorBlock.setVisibility(View.GONE);
        answerBlock.setVisibility(View.VISIBLE);

        ((TextView) findViewById(R.id.tvAnswer)).setText(answer);
        ((TextView) findViewById(R.id.tvGrounded)).setText(badge);
        TextView coverage = findViewById(R.id.tvCoverage);
        coverage.setVisibility(View.VISIBLE);
        coverage.setText(r.coveragePercent() + "%");

        // Each source opens the material itself, so every claim can be checked at its origin.
        sourcesContainer.removeAllViews();
        sourcesContainer.setVisibility(View.VISIBLE);
        for (int i = 0; i < sources.size(); i++) {
            Material m = sources.get(i);
            int n = r.passageSources.indexOf(m) + 1;
            sourcesContainer.addView(card((n > 0 ? "[" + n + "]  " : "") + m.title,
                    getString(m.typeLabelRes()) + "  •  " + getString(R.string.approved),
                    openMaterial(m)));
        }

        relatedContainer.removeAllViews();
        boolean any = !r.related.isEmpty();
        findViewById(R.id.tvRelatedTitle).setVisibility(any ? View.VISIBLE : View.GONE);
        findViewById(R.id.tvRelatedHint).setVisibility(any ? View.VISIBLE : View.GONE);
        for (final Material m : r.related) {
            relatedContainer.addView(card(m.title, getString(m.typeLabelRes()), openMaterial(m)));
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
