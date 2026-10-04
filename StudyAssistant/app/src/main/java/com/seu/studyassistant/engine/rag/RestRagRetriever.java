package com.seu.studyassistant.engine.rag;

import android.util.Log;

import com.seu.studyassistant.data.DatabaseHelper;
import com.seu.studyassistant.engine.AnswerResult;
import com.seu.studyassistant.model.Material;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * An external retrieval service over HTTP, selected with RAG_PROVIDER=rest.
 *
 * Request:  POST RAG_BASE_URL   {"query": "...", "top_k": 5, "course_id": "<id or null>"}
 *           header RAG_AUTH_HEADER: "Bearer RAG_API_KEY" (for Authorization) or the raw key
 * Response: {"results": [{"text": "...", "title": "...", "score": 0.83}, ...]}
 *           ("documents"/"chunks" and "content"/"page_content"/"source" are accepted too)
 *
 * The service is responsible for indexing only teacher-approved material. If it fails or
 * answers with something unreadable, retrieval falls back to the built-in retriever, so the
 * AI still has course context.
 */
public final class RestRagRetriever implements RagRetriever {

    private static final String TAG = "RagRetriever";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String url, apiKey, authHeader;
    private final int topK;
    private final DatabaseHelper db;
    private final RagRetriever fallback;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    RestRagRetriever(String url, String apiKey, String authHeader, int topK,
                     DatabaseHelper db, RagRetriever fallback) {
        this.url = url;
        this.apiKey = apiKey;
        this.authHeader = authHeader == null || authHeader.isEmpty() ? "Authorization" : authHeader;
        this.topK = topK > 0 ? topK : 5;
        this.db = db;
        this.fallback = fallback;
    }

    @Override public AnswerResult retrieveCourse(long courseId, String query) {
        AnswerResult r = call(query, db.courseRemoteId(courseId));
        return r != null ? r : fallback.retrieveCourse(courseId, query);
    }

    @Override public AnswerResult retrieveAll(long userId, String query) {
        AnswerResult r = call(query, null);
        return r != null ? r : fallback.retrieveAll(userId, query);
    }

    /** Null means "service unavailable": the caller falls back to the built-in retriever. */
    private AnswerResult call(String query, String courseId) {
        try {
            JSONObject body = new JSONObject().put("query", query).put("top_k", topK)
                    .put("course_id", courseId == null ? JSONObject.NULL : courseId);
            Request.Builder rb = new Request.Builder().url(url).post(RequestBody.create(body.toString(), JSON));
            if (!apiKey.isEmpty()) {
                rb.addHeader(authHeader, "Authorization".equalsIgnoreCase(authHeader) ? "Bearer " + apiKey : apiKey);
            }
            try (Response res = http.newCall(rb.build()).execute()) {
                if (!res.isSuccessful() || res.body() == null) {
                    Log.w(TAG, "RAG service HTTP " + res.code() + "; using built-in retrieval");
                    return null;
                }
                return parse(res.body().string());
            }
        } catch (Exception e) {
            Log.w(TAG, "RAG service failed (" + e.getClass().getSimpleName() + "); using built-in retrieval");
            return null;
        }
    }

    private AnswerResult parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray items = o.optJSONArray("results");
        if (items == null) items = o.optJSONArray("documents");
        if (items == null) items = o.optJSONArray("chunks");
        if (items == null) throw new IllegalStateException("no results array");

        AnswerResult r = new AnswerResult();
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < items.length() && r.passages.size() < topK; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String text = first(it, "text", "content", "page_content");
            if (text.isEmpty()) continue;
            String title = first(it, "title", "source", "name");
            // A remote passage has no local material row (id -1): it is shown, not opened.
            Material m = new Material(-1, -1, title.isEmpty() ? "Course material" : title, "notes", text, true);
            r.passages.add(text);
            r.passageSources.add(m);
            if (r.sources.size() < 3) r.sources.add(m);
            if (answer.length() > 0) answer.append("\n\n");
            if (i < 3) answer.append(text);
        }
        r.answer = answer.toString();
        r.declined = r.passages.isEmpty();
        r.coverage = r.declined ? 0 : 1;
        return r;
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
