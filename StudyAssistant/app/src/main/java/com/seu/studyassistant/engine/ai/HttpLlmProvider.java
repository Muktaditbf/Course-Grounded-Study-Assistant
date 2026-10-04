package com.seu.studyassistant.engine.ai;

import android.util.Log;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.List;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Shared HTTP plumbing for providers: one POST, status classification, cancellation, and one
 * retry for transient failures. Subclasses supply only the request and response formats.
 *
 * Logging records status codes and the provider's error message, never the key or the
 * conversation, so Logcat is safe to share when debugging.
 */
abstract class HttpLlmProvider implements LlmProvider {

    static final String TAG = "AiProvider";
    static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    final LlmConfig config;
    private final OkHttpClient http;
    private volatile Call inFlight;
    private volatile boolean cancelled;

    HttpLlmProvider(LlmConfig config, OkHttpClient http) {
        this.config = config;
        this.http = http;
    }

    /** Builds the provider-specific request, or throws if the messages cannot be encoded. */
    abstract Request buildRequest(List<ChatMessage> messages, double temperature, int maxTokens)
            throws Exception;

    /** Extracts the reply text from a successful body, or null when there is none. */
    abstract String parseReply(String body) throws Exception;

    /** Providers report "out of credit" differently; default is no distinction. */
    boolean isQuotaError(String body) { return false; }

    @Override
    public final LlmResponse complete(List<ChatMessage> messages, double temperature, int maxTokens) {
        if (!config.isComplete()) return LlmResponse.fail(LlmResponse.Status.NOT_CONFIGURED);
        cancelled = false;
        LlmResponse first = attempt(messages, temperature, maxTokens);
        if (!first.isTransient() || cancelled) return first;

        // One retry, after a short pause, only for failures the request itself did not cause.
        // Auth errors and rate limits are never retried: retrying cannot fix them and would
        // only use up more of the quota.
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmResponse.fail(LlmResponse.Status.CANCELLED);
        }
        return cancelled ? LlmResponse.fail(LlmResponse.Status.CANCELLED)
                : attempt(messages, temperature, maxTokens);
    }

    private LlmResponse attempt(List<ChatMessage> messages, double temperature, int maxTokens) {
        Request request;
        try {
            request = buildRequest(messages, temperature, maxTokens);
        } catch (Exception e) {
            Log.w(TAG, "could not build request: " + e.getClass().getSimpleName());
            return LlmResponse.fail(LlmResponse.Status.BAD_REQUEST);
        }

        Call call = http.newCall(request);
        inFlight = call;
        try (Response response = call.execute()) {
            int code = response.code();
            ResponseBody rb = response.body();
            String body = rb == null ? "" : rb.string();

            if (response.isSuccessful()) {
                String text;
                try {
                    text = parseReply(body);
                } catch (Exception e) {
                    Log.w(TAG, "malformed response from " + config.provider + ": " + e.getClass().getSimpleName());
                    return LlmResponse.fail(LlmResponse.Status.MALFORMED);
                }
                if (text == null || text.trim().isEmpty()) {
                    Log.w(TAG, "empty reply from " + config.provider);
                    return LlmResponse.fail(LlmResponse.Status.EMPTY);
                }
                return LlmResponse.ok(text.trim());
            }

            Log.w(TAG, config.provider + " HTTP " + code + ": " + errorMessage(body));
            if (code == 401 || code == 403) return LlmResponse.fail(LlmResponse.Status.AUTH_FAILED);
            if (code == 429) {
                return LlmResponse.fail(isQuotaError(body)
                        ? LlmResponse.Status.QUOTA_EXCEEDED : LlmResponse.Status.RATE_LIMITED);
            }
            if (code == 408) return LlmResponse.fail(LlmResponse.Status.TIMEOUT);
            if (code >= 500) return LlmResponse.fail(LlmResponse.Status.SERVER_ERROR);
            return LlmResponse.fail(LlmResponse.Status.BAD_REQUEST);

        } catch (SocketTimeoutException e) {
            Log.w(TAG, config.provider + " timed out");
            return LlmResponse.fail(LlmResponse.Status.TIMEOUT);
        } catch (InterruptedIOException e) {
            return LlmResponse.fail(cancelled ? LlmResponse.Status.CANCELLED : LlmResponse.Status.TIMEOUT);
        } catch (IOException e) {
            if (cancelled || call.isCanceled()) return LlmResponse.fail(LlmResponse.Status.CANCELLED);
            Log.w(TAG, config.provider + " network error: " + e.getClass().getSimpleName());
            return LlmResponse.fail(LlmResponse.Status.NETWORK);
        } finally {
            inFlight = null;
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        Call c = inFlight;
        if (c != null) c.cancel();
    }

    /** The human-readable part of an error body, shortened, for Logcat only. */
    private static String errorMessage(String body) {
        if (body == null) return "";
        try {
            org.json.JSONObject o = new org.json.JSONObject(body.trim().startsWith("[")
                    ? new org.json.JSONArray(body).getJSONObject(0).toString() : body);
            org.json.JSONObject err = o.optJSONObject("error");
            if (err != null) return err.optString("message", "").replaceAll("\\s+", " ");
        } catch (Exception ignored) {
            // Not JSON: fall through to a trimmed raw body.
        }
        String s = body.replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** Joins a base URL and a path without doubling or dropping the slash. */
    static String join(String base, String path) {
        if (base.endsWith(path)) return base;   // full endpoint given, use as is
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return path.startsWith("/") ? b + path : b + "/" + path;
    }
}
