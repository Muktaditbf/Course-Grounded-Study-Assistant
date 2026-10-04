package com.seu.studyassistant.engine;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.engine.ai.ChatMessage;
import com.seu.studyassistant.engine.ai.LlmConfig;
import com.seu.studyassistant.engine.ai.LlmProvider;
import com.seu.studyassistant.engine.ai.LlmProviders;
import com.seu.studyassistant.engine.ai.LlmResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's AI service. Screens call it with a question and retrieved course context; it builds
 * the prompt and hands the messages to whichever provider local.properties configures
 * (see LlmConfig / LlmProviders). Nothing here knows which provider that is.
 *
 * Every failure comes back as a {@link Failure} with a user-facing message - nothing is
 * allowed to fail silently, and no technical detail or key reaches the screen.
 */
public final class OpenAiClient {

    /** Marks a ViVi reply that came from the model's own knowledge rather than the courses. */
    public static final String GENERAL_MARKER = "[GENERAL]";

    /** What the model replies when the excerpts do not answer the question. */
    public static final String NOT_COVERED = "NOT_IN_MATERIAL";

    /**
     * ViVi, the app-wide assistant. Unlike the course Ask screen it answers everything, but it
     * must say where each answer came from: course excerpts are cited as [n], and anything
     * answered from general knowledge starts with GENERAL_MARKER so the app can label it.
     */
    private static final String VIVI_PROMPT =
            "You are ViVi, a friendly study assistant inside a university course app. Your name is "
          + "ViVi. If anyone asks who made, built or created you, answer that MUKTADI made you.\n"
          + "You are given (a) facts about the signed-in student and their enrolled courses - "
          + "teachers, schedules, join codes and the list of approved materials - and sometimes "
          + "(b) numbered excerpts from those materials (lecture slides, PDFs, documents).\n"
          + "How to answer:\n"
          + "1. Greetings, small talk, thanks, or questions about yourself: reply naturally and "
          + "briefly, with no marker and no citations.\n"
          + "2. Questions about the student, their courses, teachers, schedules or which materials "
          + "a course has: answer directly from the facts you were given. These are not general "
          + "knowledge, so no marker. Use the course code and title when naming a course.\n"
          + "3. Study questions: if the excerpts contain the answer, even when the topic is only "
          + "mentioned briefly, answer from them and cite the excerpt after each sentence or "
          + "bullet, like [1] or [2][3]. You may combine excerpts. Do not add facts that are not "
          + "in the excerpts to that part of the answer.\n"
          + "4. If the excerpts do not answer a study question (or none are given), answer "
          + "accurately from your own knowledge and begin your reply with exactly " + "[GENERAL]"
          + " followed by a space. Never cite excerpts in such an answer.\n"
          + "5. Never invent courses, teachers, materials or citations. If a fact (say a teacher's "
          + "name) is 'not set', say the teacher has not added it.\n"
          + "6. Keep answers short enough for a phone screen (usually under 150 words); use short "
          + "lists for steps or items. Reply in the language of the question (English or Bangla).";


    /**
     * The grounded mode, used for every student question. The model is a reader, not a source:
     * it may only restate what the numbered excerpts say, must cite them, and must answer with
     * the exact sentinel when they do not contain the answer, which the app turns into the
     * "not covered by approved material" state instead of showing a guess.
     */
    private static final String GROUNDED_PROMPT =
            "You are a study assistant inside a university course app, answering a student's "
          + "question about ONE course. You are given facts about the course (teacher, schedule, "
          + "materials) and numbered excerpts from its approved materials. Rules:\n"
          + "1. Answer from the course facts and the excerpts. A topic counts as covered even if it "
          + "is mentioned only briefly in one excerpt; combine excerpts where it helps.\n"
          + "2. After each sentence or bullet that uses an excerpt, cite it, like [1] or [2][3]. "
          + "Facts about the course itself (teacher, schedule, material list) need no citation.\n"
          + "3. You may explain or rephrase what the excerpts say so it is easy to understand, but "
          + "do not add new facts, formulas or examples that are not in them.\n"
          + "4. If only part of the question is covered, answer that part and say briefly what the "
          + "course material does not cover.\n"
          + "5. Reply with exactly " + NOT_COVERED + " and nothing else only when nothing in the "
          + "course facts or excerpts relates to the question.\n"
          + "6. Be clear and concise for a phone screen: usually under 150 words, short lists when "
          + "listing steps or items. Reply in the same language as the question (English or Bangla).";


    /** Why a request failed, so the UI can show the right message. */
    public enum Failure { NONE, NO_KEY, NO_NETWORK, TIMEOUT, UNAUTHORISED, RATE_LIMITED, NO_CREDIT, SERVER, UNKNOWN }

    /** One outcome: either {@code text} is set, or {@code failure} explains why it is not. */
    public static final class Result {
        public final String text;
        public final Failure failure;

        private Result(String text, Failure failure) {
            this.text = text;
            this.failure = failure;
        }

        public static Result ok(String text) { return new Result(text, Failure.NONE); }
        public static Result fail(Failure f) { return new Result(null, f); }

        public boolean isOk() { return failure == Failure.NONE && text != null; }

        /** The message to show the user for this outcome. */
        public int messageRes() {
            switch (failure) {
                case NO_KEY:        return R.string.ai_err_no_key;
                case NO_NETWORK:    return R.string.ai_err_no_network;
                case TIMEOUT:       return R.string.ai_err_timeout;
                case UNAUTHORISED:  return R.string.ai_err_unauthorised;
                case RATE_LIMITED:  return R.string.ai_err_rate_limited;
                case NO_CREDIT:     return R.string.ai_err_no_credit;
                case SERVER:        return R.string.ai_err_server;
                default:            return R.string.ai_err_unknown;
            }
        }
    }

    private final LlmConfig config = LlmConfig.fromBuild();
    private final LlmProvider provider = LlmProviders.create(config);

    /** True when an AI key and model are configured. */
    public static boolean hasKey() { return LlmConfig.fromBuild().isComplete(); }

    public static boolean isOnline(Context c) {
        ConnectivityManager cm =
                (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;   // cannot tell; let the call itself decide
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** Stops this screen's request in flight; called when the screen is destroyed. */
    public void cancel() { provider.cancel(); }

    // ------------------------------------------------------------------- ViVi

    /**
     * ViVi's reply. {@code excerpts} may be empty: then ViVi answers small talk or from general
     * knowledge. {@code studyContext} (see StudyContext) describes the student and their courses,
     * so questions like "who teaches CSE346?" are answered. Blocking: run off the main thread.
     */
    public Result askHybrid(Context ctx, String question, List<String> excerpts,
                            List<String> titles, @Nullable String studyContext,
                            @Nullable String history) {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system(VIVI_PROMPT));
        if (studyContext != null && !studyContext.isEmpty()) m.add(ChatMessage.system(studyContext));
        if (excerpts != null && !excerpts.isEmpty()) {
            m.add(ChatMessage.system("Course excerpts (teacher-approved):\n" + numbered(excerpts, titles)));
        }
        m.add(ChatMessage.user(history == null || history.isEmpty() ? question
                : history + "\n\nCurrent message: " + question));
        return run(ctx, m, 0.4);
    }

    // ------------------------------------------------------------- course Ask

    /**
     * Grounded answer for the course Ask screen: blocking, run it off the main thread.
     * Excerpts are numbered in order and labelled with their material title so the model can
     * cite them.
     */
    public Result askGrounded(Context ctx, String question, List<String> excerpts,
                              List<String> titles, @Nullable String courseInfo,
                              @Nullable String history) {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system(GROUNDED_PROMPT));
        if (courseInfo != null && !courseInfo.isEmpty()) m.add(ChatMessage.system(courseInfo));
        m.add(ChatMessage.system(excerpts == null || excerpts.isEmpty()
                ? "Course excerpts: none matched this question."
                : "Course excerpts:\n" + numbered(excerpts, titles)));
        m.add(ChatMessage.user(history == null || history.isEmpty() ? question
                : history + "\n\nCurrent question: " + question));
        return run(ctx, m, 0.2);
    }

    // ---------------------------------------------------------------- shared

    /**
     * Some models cite as 【1】 or 【1†L3-L5】 instead of [1]. Rewritten to [n] so citations are
     * recognised (and their sources listed) whichever model is configured.
     */
    static String normalizeCitations(String text) {
        return text.replaceAll("【\\s*(\\d{1,3})[^】]*】", "[$1]");
    }

    private static String numbered(List<String> excerpts, List<String> titles) {
        StringBuilder e = new StringBuilder();
        for (int i = 0; i < excerpts.size(); i++) {
            String title = titles != null && i < titles.size() ? titles.get(i) : "";
            e.append('[').append(i + 1).append("] (").append(title).append(")\n")
                    .append(excerpts.get(i)).append("\n\n");
        }
        return e.toString();
    }

    private Result run(Context ctx, List<ChatMessage> messages, double temperature) {
        if (!config.isComplete()) return Result.fail(Failure.NO_KEY);
        if (!isOnline(ctx)) return Result.fail(Failure.NO_NETWORK);

        LlmResponse r = provider.complete(messages, temperature, config.maxTokens);
        if (r.isOk()) return Result.ok(normalizeCitations(r.text));
        switch (r.status) {
            case NOT_CONFIGURED: return Result.fail(Failure.NO_KEY);
            case AUTH_FAILED:    return Result.fail(Failure.UNAUTHORISED);
            case RATE_LIMITED:   return Result.fail(Failure.RATE_LIMITED);
            case QUOTA_EXCEEDED: return Result.fail(Failure.NO_CREDIT);
            case SERVER_ERROR:   return Result.fail(Failure.SERVER);
            case TIMEOUT:        return Result.fail(Failure.TIMEOUT);
            case NETWORK:        return Result.fail(Failure.NO_NETWORK);
            default:             return Result.fail(Failure.UNKNOWN);   // malformed, empty, bad request, cancelled
        }
    }
}
