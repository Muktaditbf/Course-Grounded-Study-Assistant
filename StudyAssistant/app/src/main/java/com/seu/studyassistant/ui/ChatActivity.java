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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;
import com.seu.studyassistant.engine.AnswerResult;
import com.seu.studyassistant.engine.OpenAiClient;
import com.seu.studyassistant.engine.RetrievalEngine;
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

    private RetrievalEngine engine;
    private final OpenAiClient ai = new OpenAiClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean busy;

    /** One bubble. */
    static class Msg {
        static final int USER = 0, AI = 1, TYPING = 2;
        final int kind;
        final String text;
        final String sources;      // null when the answer was not grounded
        Msg(int kind, String text, String sources) {
            this.kind = kind; this.text = text; this.sources = sources;
        }
    }

    @Override
    protected boolean showsAiBubble() { return false; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        setupHeader(getString(R.string.ai_chat_title), true);

        engine = new RetrievalEngine(db);
        recycler = findViewById(R.id.recycler);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);          // newest message sits at the bottom, like a chat
        recycler.setLayoutManager(lm);

        adapter = new ChatAdapter();
        recycler.setAdapter(adapter);

        messages.add(new Msg(Msg.AI, getString(R.string.ai_chat_welcome), null));
        adapter.notifyDataSetChanged();

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(); }
        });

        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            send();
            return true;
        });

        setHeaderAction(getString(R.string.clear_chat), new View.OnClickListener() {
            @Override public void onClick(View v) {
                messages.clear();
                messages.add(new Msg(Msg.AI, getString(R.string.ai_chat_welcome), null));
                adapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    protected void onDestroy() {
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
        add(new Msg(Msg.USER, question, null));
        add(new Msg(Msg.TYPING, "", null));
        busy = true;

        // The free tier's daily cap covers the chat as well as the per-course Ask screen.
        if ("free".equals(u.tier) && db.questionsToday(u.id) >= FREE_DAILY_LIMIT) {
            removeTyping();
            busy = false;
            add(new Msg(Msg.AI, getString(R.string.cap_reached, FREE_DAILY_LIMIT), null));
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
                final AnswerResult r = engine.askEverything(userId, retrievalQuery);
                final OpenAiClient.Result res;
                if (r.declined) {
                    res = null;
                } else {
                    java.util.List<String> titles = new java.util.ArrayList<>();
                    for (Material m : r.passageSources) titles.add(m.title);
                    res = ai.askGrounded(getApplicationContext(), question, r.passages, titles, history);
                }

                main.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        busy = false;
                        removeTyping();

                        // Strict grounding, as on the Ask screen: no guessing outside the course.
                        if (r.declined || (res != null && res.isOk()
                                && res.text.trim().startsWith(OpenAiClient.NOT_COVERED))) {
                            db.logQuestion(u.id, -1, question, false);
                            add(new Msg(Msg.AI, getString(R.string.chat_not_covered), null));
                            return;
                        }
                        db.logQuestion(u.id, -1, question, true);
                        if (res == null || !res.isOk()) {
                            // No AI available: show the teacher's own passages with their sources.
                            add(new Msg(Msg.AI, r.answer, sourceNames(r.sources)));
                            return;
                        }
                        add(new Msg(Msg.AI, res.text,
                                sourceNames(AskQuestionActivity.citedSources(res.text, r))));
                    }
                });
            }
        });
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

    private String sourceNames(List<Material> sources) {
        if (sources == null || sources.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Material m : sources) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(m.title);
        }
        return getString(R.string.ai_sources_prefix, sb.toString());
    }

    private void add(Msg m) {
        messages.add(m);
        adapter.notifyItemInserted(messages.size() - 1);
        recycler.scrollToPosition(messages.size() - 1);
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

    // ---------------------------------------------------------------- adapter

    private class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override public int getItemViewType(int position) { return messages.get(position).kind; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == Msg.USER ? R.layout.item_chat_user
                    : viewType == Msg.TYPING ? R.layout.item_chat_typing
                    : R.layout.item_chat_ai;
            return new Holder(getLayoutInflater().inflate(layout, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Msg m = messages.get(position);
            if (m.kind == Msg.TYPING) return;

            Holder h = (Holder) holder;
            h.text.setText(m.text);
            if (h.sources != null) {
                boolean show = m.sources != null;
                h.sources.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) h.sources.setText(m.sources);
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
