package com.seu.studyassistant.engine.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Google's native Gemini API: POST {base}/models/{model}:generateContent with the key in the
 * x-goog-api-key header. System messages become the systemInstruction.
 */
final class GeminiProvider extends HttpLlmProvider {

    static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta";

    GeminiProvider(LlmConfig config, OkHttpClient http) { super(config, http); }

    @Override
    Request buildRequest(List<ChatMessage> messages, double temperature, int maxTokens) throws Exception {
        StringBuilder system = new StringBuilder();
        JSONArray contents = new JSONArray();
        for (ChatMessage m : messages) {
            if (m.role == ChatMessage.Role.SYSTEM) {
                if (system.length() > 0) system.append("\n\n");
                system.append(m.content);
                continue;
            }
            contents.put(new JSONObject()
                    .put("role", m.role == ChatMessage.Role.ASSISTANT ? "model" : "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", m.content))));
        }
        JSONObject body = new JSONObject()
                .put("contents", contents)
                .put("generationConfig", new JSONObject()
                        .put("temperature", temperature)
                        .put("maxOutputTokens", maxTokens));
        if (system.length() > 0) {
            body.put("systemInstruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", system.toString()))));
        }
        String base = config.baseUrl.isEmpty() ? DEFAULT_BASE : config.baseUrl;
        return new Request.Builder()
                .url(join(base, "/models/" + config.model + ":generateContent"))
                .addHeader("x-goog-api-key", config.apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }

    @Override
    String parseReply(String body) throws Exception {
        JSONArray candidates = new JSONObject(body).optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return null;
        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) out.append(parts.getJSONObject(i).optString("text", ""));
        return out.toString();
    }

    @Override
    boolean isQuotaError(String body) {
        return body != null && body.contains("RESOURCE_EXHAUSTED") && body.contains("quota");
    }
}
