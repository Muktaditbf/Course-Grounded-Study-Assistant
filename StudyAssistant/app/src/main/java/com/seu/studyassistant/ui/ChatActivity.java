package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
 * The always-available study assistant.
 *
 * Unlike the per-course Ask screen, this one is not told which topic it is about: it retrieves
 * across every course the student is enrolled in, so a question can be asked without first
 * navigating to the right course. That is the whole point - a student rarely knows in advance
 * which lecture holds the answer.
 *
 * The Content Lock still applies. Retrieval only ever loads approved material from courses
 * this user is enrolled in, so the assistant can be friendly and general without ever leaking
 * a document a teacher has not released.
 */
public class ChatActivity extends BaseActivity {

    /** How many earlier turns to replay, so follow-ups like "explain that simpler" work. */
    private static final int MEMORY_TURNS = 6;

    private RecyclerView recycler;
    private EditText etMessage;
    private View btnSend;

    private final List<Msg> messages = new ArrayList<>();
    private ChatAdapter adapter;

    private com.seu.studyassistant.engine.rag.RagRetriever rag;
    private final OpenAiClient ai = new OpenAiClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean busy;
    private View sendProgress;

    /** Bubbles never span the whole screen: at most about 78% of its width. */
    private int bubbleMaxWidth() {
        return (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
    }

    private void scrollToLatest() {
        if (!messages.isEmpty()) recycler.post(() -> recycler.smoothScrollToPosition(messages.size() - 1));
    }

    /** Send turns into a spinner while ViVi answers, so a second tap cannot send twice. */
    private void setBusy(boolean b) {
        busy = b;
        btnSend.setEnabled(!b);
        btnSend.setAlpha(b ? 0.55f : 1f);
        if (sendProgress != null) sendProgress.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    /** Opens ViVi with this question already asked (from the course Ask screen). */
    public static final String EXTRA_QUESTION = "question";

    /** One bubble. */
    static class Msg {
        static final int USER = 0, AI = 1, TYPING = 2;
        /** Where an AI answer came from, shown as a small label under the bubble. */
        static final int LABEL_NONE = 0, LABEL_COURSE = 1, LABEL_GENERAL = 2;
        final int kind;
        final String text;
        final int label;
        final List<Material> sources;   // the cited materials, for LABEL_COURSE
        Msg(int kind, String text) { this(kind, text, LABEL_NONE, null); }
        Msg(int kind, String text, int label, List<Material> sources) {
            this.kind = kind; this.text = text; this.label = label;
            this.sources = sources == null ? new ArrayList<Material>() : sources;
        }
    }

    @Override
    protected boolean showsAiBubble() { return false; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        setupHeader(getString(R.string.ai_chat_title), true);

        rag = com.seu.studyassistant.engine.rag.RagRetrievers.create(db);
        recycler = findViewById(R.id.recycler);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);

        // Messages start at the top; each new one scrolls into view. (Stacking from the end
        // left a short conversation floating at the bottom under a large empty area.)
        recycler.setLayoutManager(new LinearLayoutManager(this));
        sendProgress = findViewById(R.id.sendProgress);
        // When the keyboard opens the list gets shorter: keep the newest message visible.
        recycler.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b < ob) scrollToLatest();
        });

        adapter = new ChatAdapter();
        recycler.setAdapter(adapter);

        messages.add(new Msg(Msg.AI, getString(R.string.ai_chat_welcome)));
        adapter.notifyDataSetChanged();

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(); }
        });

        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            send();
            return true;
        });

        // Arriving from a course's "Ask ViVi instead": ask that question straight away.
        String handed = getIntent().getStringExtra(EXTRA_QUESTION);
        if (handed != null && !handed.trim().isEmpty() && savedInstanceState == null) {
            etMessage.setText(handed);
            send();
        }

        setHeaderAction(getString(R.string.clear_chat), new View.OnClickListener() {
            @Override public void onClick(View v) {
                messages.clear();
                messages.add(new Msg(Msg.AI, getString(R.string.ai_chat_welcome)));
                adapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    protected void onDestroy() {
        ai.cancel();
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------- send

    private void send() {
        if (busy) return;

        final User u = currentUser();
        if (u == null) { logout(); return; }

        final String question = etMessage.getText().toString().trim();
        if (question.isEmpty()) return;

        etMessage.setText("");
        add(new Msg(Msg.USER, question));
        add(new Msg(Msg.TYPING, ""));
        setBusy(true);

        // The free tier's daily cap covers the chat as well as the per-course Ask screen.
        if ("free".equals(u.tier) && db.questionsToday(u.id) >= FREE_DAILY_LIMIT) {
            removeTyping();
            setBusy(false);
            add(new Msg(Msg.AI, getString(R.string.cap_reached, FREE_DAILY_LIMIT)));
            return;
        }

        final String history = recentHistory();
        final long userId = u.id;
        // A short follow-up ("explain that simpler") has nothing to search for on its own, so
        // retrieval also uses the previous question.
        final String retrievalQuery = RetrievalEngine.tokenize(question).size() < 3
                ? question + " " + previousUserQuestion() : question;

        // Retrieval runs with the network call, off the main thread, so typing never stalls.
        io.execute(new Runnable() {
            @Override public void run() {
                // RAG across every enrolled course; only approved material is ever searched.
                final AnswerResult r = rag.retrieveAll(userId, retrievalQuery);
                List<String> excerpts = new ArrayList<>(), titles = new ArrayList<>();
                if (!r.declined) {
                    excerpts = r.passages;
                    for (Material m : r.passageSources) titles.add(m.title);
                }
                List<String> courses = new ArrayList<>();
                for (Course c : db.coursesForStudent(userId)) courses.add(c.code + " - " + c.title);

                final OpenAiClient.Result res = ai.askHybrid(getApplicationContext(), question,
                        excerpts, titles, courses, history);

                main.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        setBusy(false);
                        removeTyping();

                        if (res.isOk()) {
                            db.logQuestion(u.id, -1, question, true);
                            add(parseReply(res.text, r));
                        } else if (!r.declined) {
                            // No AI available: the teacher's own passages still answer it.
                            db.logQuestion(u.id, -1, question, true);
                            add(new Msg(Msg.AI, r.answer, Msg.LABEL_COURSE, r.sources));
                        } else {
                            add(new Msg(Msg.AI, getString(res.messageRes())));
                        }
                    }
                });
            }
        });
    }

    /**
     * Turns ViVi's reply into a bubble and its label. A reply starting with the general marker
     * came from the model's own knowledge; explicit [n] citations mean it came from the
     * courses; neither (greetings, small talk, "who made you") gets no label at all.
     */
    static Msg parseReply(String raw, AnswerResult r) {
        String text = raw.trim();
        String marker = OpenAiClient.GENERAL_MARKER;
        if (text.regionMatches(true, 0, marker, 0, marker.length())) {
            return new Msg(Msg.AI, text.substring(marker.length()).trim(), Msg.LABEL_GENERAL, null);
        }
        // A marker the model put mid-reply still means general knowledge; drop it from the text.
        if (text.contains(marker)) {
            return new Msg(Msg.AI, text.replace(marker, "").trim(), Msg.LABEL_GENERAL, null);
        }
        List<Material> cited = explicitCitations(text, r);
        return cited.isEmpty()
                ? new Msg(Msg.AI, text)
                : new Msg(Msg.AI, text, Msg.LABEL_COURSE, cited);
    }

    /** Materials cited as [n] in the reply - only ones actually cited, never a guess. */
    static List<Material> explicitCitations(String text, AnswerResult r) {
        List<Material> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d{1,3})]").matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1)) - 1;
            if (n >= 0 && n < r.passageSources.size()) {
                Material mat = r.passageSources.get(n);
                if (seen.add(mat.id)) out.add(mat);
            }
        }
        return out;
    }

    private String previousUserQuestion() {
        // The newest USER message is the one just added; look before it.
        boolean skippedCurrent = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m.kind != Msg.USER) continue;
            if (!skippedCurrent) { skippedCurrent = true; continue; }
            return m.text;
        }
        return "";
    }

    /**
     * Replays the last few turns as plain text.
     *
     * The assistant is stateless per request, so without this a follow-up such as "give me an
     * example of that" would arrive with no idea what "that" was.
     */
    private String recentHistory() {
        int from = Math.max(0, messages.size() - 1 - MEMORY_TURNS * 2);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < messages.size(); i++) {
            Msg m = messages.get(i);
            if (m.kind == Msg.TYPING) continue;
            if (m.text == null || m.text.isEmpty()) continue;
            sb.append(m.kind == Msg.USER ? "Student: " : "Assistant: ").append(m.text).append('\n');
        }
        // Drop the question itself; it is appended by the caller as the live turn.
        return sb.length() == 0 ? "" : "Earlier in this conversation:\n" + sb;
    }

    private void add(Msg m) {
        messages.add(m);
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToLatest();
    }

    private void removeTyping() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).kind == Msg.TYPING) {
                messages.remove(i);
                adapter.notifyItemRemoved(i);
                return;
            }
        }
    }

    /** A source label opens its material, or offers a choice when the answer cited several. */
    private void openSources(List<Material> all) {
        // Passages from an external retrieval service have no local material to open.
        final List<Material> sources = new ArrayList<>();
        for (Material m : all) if (m.id > 0) sources.add(m);
        if (sources.isEmpty()) return;
        if (sources.size() == 1) {
            open(MaterialViewActivity.class, EXTRA_MATERIAL_ID, sources.get(0).id);
            return;
        }
        String[] names = new String[sources.size()];
        for (int i = 0; i < names.length; i++) names[i] = sources.get(i).title;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.choose_source)
                .setItems(names, (d, which) ->
                        open(MaterialViewActivity.class, EXTRA_MATERIAL_ID, sources.get(which).id))
                .show();
    }

    // ---------------------------------------------------------------- adapter

    private class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override public int getItemViewType(int position) { return messages.get(position).kind; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == Msg.USER ? R.layout.item_chat_user
                    : viewType == Msg.TYPING ? R.layout.item_chat_typing
                    : R.layout.item_chat_ai;
            Holder h = new Holder(getLayoutInflater().inflate(layout, parent, false));
            if (h.text != null) h.text.setMaxWidth(bubbleMaxWidth());
            if (h.sources != null) h.sources.setMaxWidth(bubbleMaxWidth());
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Msg m = messages.get(position);
            if (m.kind == Msg.TYPING) return;

            Holder h = (Holder) holder;
            h.text.setText(m.text);
            if (h.sources == null) return;

            if (m.label == Msg.LABEL_COURSE && !m.sources.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (Material mat : m.sources) {
                    if (names.length() > 0) names.append(" · ");
                    names.append(mat.title);
                }
                h.sources.setVisibility(View.VISIBLE);
                h.sources.setText(getString(R.string.label_from_courses, names));
                h.sources.setBackgroundResource(R.drawable.bg_label_course);
                h.sources.setTextColor(ContextCompat.getColor(ChatActivity.this, R.color.locked_green_text));
                final List<Material> src = m.sources;
                h.sources.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openSources(src); }
                });
            } else if (m.label == Msg.LABEL_GENERAL) {
                h.sources.setVisibility(View.VISIBLE);
                h.sources.setText(R.string.label_general);
                h.sources.setBackgroundResource(R.drawable.bg_label_general);
                h.sources.setTextColor(ContextCompat.getColor(ChatActivity.this, R.color.violet));
                h.sources.setOnClickListener(null);
            } else {
                h.sources.setVisibility(View.GONE);
            }
        }

        @Override public int getItemCount() { return messages.size(); }
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView text;
        final TextView sources;
        Holder(View v) {
            super(v);
            text = v.findViewById(R.id.msgText);
            sources = v.findViewById(R.id.msgSources);
        }
    }
}
