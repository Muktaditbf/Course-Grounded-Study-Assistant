package com.seu.studyassistant.engine.ai;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * Picks the provider adapter named by LLM_PROVIDER. Adding a provider means adding one adapter
 * class and one case here; no screen or prompt code changes.
 */
public final class LlmProviders {

    private LlmProviders() {}

    /** One shared connection pool for every AI request in the app. */
    private static OkHttpClient shared;

    private static synchronized OkHttpClient http(LlmConfig c) {
        if (shared == null) {
            shared = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(c.timeoutSeconds, TimeUnit.SECONDS)
                    .callTimeout(c.timeoutSeconds + 15, TimeUnit.SECONDS)
                    .build();
        }
        return shared;
    }

    public static LlmProvider create(LlmConfig c) {
        // A fresh adapter per caller (each screen) so cancelling one screen's request never
        // cancels another's; the HTTP client underneath is shared.
        switch (c.provider) {
            case "gemini":
                return new GeminiProvider(c, http(c));
            case "openai_compatible":
            case "openai":
            case "groq":
            case "openrouter":
            default:
                return new OpenAiCompatibleProvider(c, http(c));
        }
    }
}
