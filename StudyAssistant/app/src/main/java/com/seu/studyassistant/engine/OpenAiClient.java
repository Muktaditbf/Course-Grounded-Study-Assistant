package com.seu.studyassistant.engine;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.annotation.Nullable;

import com.seu.studyassistant.BuildConfig;
import com.seu.studyassistant.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to a Chat Completions endpoint.
 *
 * Every failure is mapped to a specific, user-facing reason rather than a silent no-op:
 * a request that goes wrong always comes back with something the UI can put on screen.
 * The key is read from BuildConfig, which is populated from local.properties at build time,
 * and is never logged - not even at the point where a 401 proves it is wrong.
 */
public final class OpenAiClient {

    /**
     * Set from local.properties at build time. Defaults to OpenAI, but any provider that
     * implements the Chat Completions API - Gemini, Groq, OpenRouter, Cerebras - works by
     * changing AI_BASE_URL and AI_MODEL alone.
     */
    private static final String ENDPOINT = BuildConfig.AI_BASE_URL;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * Tone only, no topic restriction: this assistant answers whatever it is asked. When the
     * caller has approved course material for the question, that text is appended as context.
     */
    private static final String SYSTEM_PROMPT =
            "You are a friendly study assistant for university students, used inside a phone app. "
          + "Answer clearly and directly in plain language. Keep answers short enough to read on "
          + "a phone screen - usually under 120 words - and use a short list when steps or items "
          + "genuinely help. Do not invent citations.";

    /** What the model replies when the excerpts do not answer the question. */
    public static final String NOT_COVERED = "NOT_IN_MATERIAL";

    /**
     * The grounded mode, used for every student question. The model is a reader, not a source:
     * it may only restate what the numbered excerpts say, must cite them, and must answer with
     * the exact sentinel when they do not contain the answer, which the app turns into the
     * "not covered by approved material" state instead of showing a guess.
     */
    private static final String GROUNDED_PROMPT =
            "You are a study assistant inside a university course app. Answer the student's question "
          + "using ONLY the numbered course excerpts provided. Rules:\n"
          + "1. Every fact in your answer must come from the excerpts. Do not add outside knowledge, "
          + "examples, formulas or definitions that are not in them.\n"
          + "2. After each sentence or bullet, cite the excerpt it came from, like [1] or [2][3].\n"
          + "3. If the excerpts do not contain enough to answer, reply with exactly " + NOT_COVERED
          + " and nothing else. Partial answers are fine when you say which part is not covered.\n"
          + "4. Be clear and concise for a phone screen: usually under 150 words, short lists when "
          + "listing steps or items.\n"
          + "5. Reply in the same language as the question (English or Bangla).";

    /** Why a request failed, so the UI can show the right message. */
    public enum Failure { NONE, NO_KEY, NO_NETWORK, UNAUTHORISED, RATE_LIMITED, NO_CREDIT, SERVER, UNKNOWN }

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
                case UNAUTHORISED:  return R.string.ai_err_unauthorised;
                case RATE_LIMITED:  return R.string.ai_err_rate_limited;
                case NO_CREDIT:     return R.string.ai_err_no_credit;
                case SERVER:        return R.string.ai_err_server;
                default:            return R.string.ai_err_unknown;
            }
        }
    }

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build();

    public static boolean hasKey() {
        String k = BuildConfig.OPENAI_API_KEY;
        return k != null && !k.trim().isEmpty() && !k.startsWith("YOUR_KEY");
    }

    public static boolean isOnline(Context c) {
        ConnectivityManager cm =
                (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;   // cannot tell; let the call itself decide
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Blocking call - run it off the main thread.
     *
     * @param courseContext approved course text to ground the answer in, or null for a plain
     *                      general-knowledge answer.
     */
    public Result ask(Context ctx, String question, @Nullable String courseContext) {
        if (!hasKey()) return Result.fail(Failure.NO_KEY);
        if (!isOnline(ctx)) return Result.fail(Failure.NO_NETWORK);

        String body;
        try {
            body = buildRequest(question, courseContext);
        } catch (Exception e) {
            return Result.fail(Failure.UNKNOWN);
        }

        Request request = new Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY)
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            int code = response.code();
            if (code == 401 || code == 403) return Result.fail(Failure.UNAUTHORISED);
            if (code >= 500) return Result.fail(Failure.SERVER);

            ResponseBody rb = response.body();
            String payload = rb == null ? "" : rb.string();

            // OpenAI returns 429 both for genuine rate limiting and for an account with no
            // credits left. They need different advice, so the error code decides which.
            if (code == 429) {
                return Result.fail(isOutOfCredit(payload)
                        ? Failure.NO_CREDIT : Failure.RATE_LIMITED);
            }
            if (!response.isSuccessful()) return Result.fail(Failure.UNKNOWN);

            String answer = parseAnswer(payload);
            return answer == null ? Result.fail(Failure.UNKNOWN) : Result.ok(answer);

        } catch (IOException e) {
            // Timeouts and DNS failures land here even when the network looked available.
            return Result.fail(Failure.NO_NETWORK);
        } catch (Exception e) {
            return Result.fail(Failure.UNKNOWN);
        }
    }

    /**
     * Grounded answer: blocking, run it off the main thread. Excerpts are numbered in order and
     * labelled with their material title so the model can cite them.
     */
    public Result askGrounded(Context ctx, String question, java.util.List<String> excerpts,
                              java.util.List<String> titles, @Nullable String history) {
        if (!hasKey()) return Result.fail(Failure.NO_KEY);
        if (!isOnline(ctx)) return Result.fail(Failure.NO_NETWORK);
        String body;
        try {
            StringBuilder ctxText = new StringBuilder("Course excerpts:\n");
            for (int i = 0; i < excerpts.size(); i++) {
                ctxText.append('[').append(i + 1).append("] (").append(titles.get(i)).append(")\n")
                        .append(excerpts.get(i)).append("\n\n");
            }
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", GROUNDED_PROMPT));
            messages.put(new JSONObject().put("role", "system").put("content", ctxText.toString()));
            String q = history == null || history.isEmpty() ? question
                    : history + "\n\nCurrent question: " + question;
            messages.put(new JSONObject().put("role", "user").put("content", q));
            body = new JSONObject()
                    .put("model", BuildConfig.OPENAI_MODEL)
                    .put("messages", messages)
                    .put("temperature", 0.1)   // faithful restatement, not creativity
                    .put("max_tokens", 600)
                    .toString();
        } catch (Exception e) {
            return Result.fail(Failure.UNKNOWN);
        }
        return post(body);
    }

    private Result post(String body) {
        Request request = new Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY)
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            int code = response.code();
            if (code == 401 || code == 403) return Result.fail(Failure.UNAUTHORISED);
            if (code >= 500) return Result.fail(Failure.SERVER);
            ResponseBody rb = response.body();
            String payload = rb == null ? "" : rb.string();
            if (code == 429) {
                return Result.fail(isOutOfCredit(payload) ? Failure.NO_CREDIT : Failure.RATE_LIMITED);
            }
            if (!response.isSuccessful()) return Result.fail(Failure.UNKNOWN);
            String answer = parseAnswer(payload);
            return answer == null ? Result.fail(Failure.UNKNOWN) : Result.ok(answer);
        } catch (IOException e) {
            return Result.fail(Failure.NO_NETWORK);
        } catch (Exception e) {
            return Result.fail(Failure.UNKNOWN);
        }
    }

    private String buildRequest(String question, @Nullable String courseContext) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));

        if (courseContext != null && !courseContext.trim().isEmpty()) {
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "The student's teacher has approved the course material below. "
                            + "Prefer it when it answers the question, and say so naturally. If it "
                            + "does not cover the question, answer from your own knowledge instead.\n\n"
                            + courseContext));
        }

        messages.put(new JSONObject().put("role", "user").put("content", question));

        return new JSONObject()
                .put("model", BuildConfig.OPENAI_MODEL)
                .put("messages", messages)
                .put("temperature", 0.4)
                .put("max_tokens", 500)
                .toString();
    }

    private boolean isOutOfCredit(String payload) {
        try {
            JSONObject err = new JSONObject(payload).optJSONObject("error");
            if (err == null) return false;
            String type = err.optString("type", "");
            String code = err.optString("code", "");
            return type.contains("insufficient_quota") || code.contains("credit_balance");
        } catch (Exception e) {
            return false;
        }
    }

    private String parseAnswer(String json) {
        try {
            JSONArray choices = new JSONObject(json).optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            String text = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content", "")
                    .trim();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            return null;
        }
    }
}
