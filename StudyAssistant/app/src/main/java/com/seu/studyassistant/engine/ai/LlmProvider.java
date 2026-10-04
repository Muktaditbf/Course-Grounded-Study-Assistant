package com.seu.studyassistant.engine.ai;

import java.util.List;

/**
 * A language-model backend. Each implementation adapts one wire format (OpenAI-compatible
 * Chat Completions, Gemini generateContent, ...); the rest of the app only sees this interface.
 * Implementations are blocking and must be called off the main thread.
 */
public interface LlmProvider {

    LlmResponse complete(List<ChatMessage> messages, double temperature, int maxTokens);

    /** Aborts the request in flight, if any. Safe to call from any thread. */
    void cancel();
}
