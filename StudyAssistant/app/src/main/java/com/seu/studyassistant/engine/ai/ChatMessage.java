package com.seu.studyassistant.engine.ai;

/** One message sent to a language model, independent of any provider's wire format. */
public final class ChatMessage {
    public enum Role { SYSTEM, USER, ASSISTANT }

    public final Role role;
    public final String content;

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content == null ? "" : content;
    }

    public static ChatMessage system(String c) { return new ChatMessage(Role.SYSTEM, c); }
    public static ChatMessage user(String c) { return new ChatMessage(Role.USER, c); }
}
