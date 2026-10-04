package com.seu.studyassistant.engine.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * The OpenAI Chat Completions format: POST {base}/chat/completions with a Bearer key.
 * Covers OpenAI, Groq, OpenRouter, Together, Mistral, and Gemini's OpenAI-compatible endpoint,
 * which differ only in base URL and model name.
 */
final class OpenAiCompatibleProvider extends HttpLlmProvider {

    OpenAiCompatibleProvider(LlmConfig config, OkHttpClient http) { super(config, http); }

    @Override
    Request buildRequest(List<ChatMessage> messages, double temperature, int maxTokens) throws Exception {
        JSONArray msgs = new JSONArray();
        for (ChatMessage m : messages) {
            String role = m.role == ChatMessage.Role.SYSTEM ? "system"
                    : m.role == ChatMessage.Role.ASSISTANT ? "assistant" : "user";
            msgs.put(new JSONObject().put("role", role).put("content", m.content));
        }
        String body = new JSONObject()
                .put("model", config.model)
                .put("messages", msgs)
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
                .toString();
        return new Request.Builder()
                .url(join(config.baseUrl, "/chat/completions"))
                .addHeader("Authorization", "Bearer " + config.apiKey)
                .post(RequestBody.create(body, JSON))
                .build();
    }

    @Override
    String parseReply(String body) throws Exception {
        JSONArray choices = new JSONObject(body).optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        return message == null || message.isNull("content") ? null : message.optString("content", null);
    }

    @Override
    boolean isQuotaError(String body) {
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err == null) return false;
            String all = err.optString("type", "") + " " + err.optString("code", "");
            return all.contains("insufficient_quota") || all.contains("credit");
        } catch (Exception e) {
            return false;
        }
    }
}
