package com.seu.studyassistant.engine.ai;

import com.seu.studyassistant.BuildConfig;

/**
 * The AI configuration, read from local.properties at build time (via BuildConfig).
 *
 *   LLM_PROVIDER         openai_compatible | gemini
 *   LLM_BASE_URL         e.g. https://api.groq.com/openai/v1
 *   LLM_MODEL            e.g. openai/gpt-oss-120b
 *   LLM_API_KEY          the provider's key (never logged, never committed)
 *   LLM_TIMEOUT_SECONDS  read timeout
 *   LLM_MAX_TOKENS       output cap per reply
 *
 * The older OPENAI_API_KEY / AI_BASE_URL / AI_MODEL names are still accepted by the build.
 */
public final class LlmConfig {

    public final String provider, baseUrl, model, apiKey;
    public final int timeoutSeconds, maxTokens;

    LlmConfig(String provider, String baseUrl, String model, String apiKey,
              int timeoutSeconds, int maxTokens) {
        this.provider = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 45;
        this.maxTokens = maxTokens > 0 ? maxTokens : 800;
    }

    public static LlmConfig fromBuild() {
        return new LlmConfig(BuildConfig.LLM_PROVIDER, BuildConfig.LLM_BASE_URL,
                BuildConfig.LLM_MODEL, BuildConfig.LLM_API_KEY,
                BuildConfig.LLM_TIMEOUT_SECONDS, BuildConfig.LLM_MAX_TOKENS);
    }

    /** True when there is enough to make a request at all. */
    public boolean isComplete() {
        return !apiKey.isEmpty() && !model.isEmpty() && !apiKey.startsWith("YOUR_");
    }
}
