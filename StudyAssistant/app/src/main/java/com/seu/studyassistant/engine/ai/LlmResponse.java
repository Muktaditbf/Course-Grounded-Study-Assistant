package com.seu.studyassistant.engine.ai;

/**
 * What a provider returned, already classified. Providers translate their own status codes
 * and error bodies into these outcomes, so the app never handles provider-specific errors.
 */
public final class LlmResponse {

    public enum Status {
        OK, NOT_CONFIGURED, AUTH_FAILED, RATE_LIMITED, QUOTA_EXCEEDED, BAD_REQUEST,
        SERVER_ERROR, TIMEOUT, NETWORK, MALFORMED, EMPTY, CANCELLED
    }

    public final Status status;
    public final String text;

    private LlmResponse(Status status, String text) {
        this.status = status;
        this.text = text;
    }

    public static LlmResponse ok(String text) { return new LlmResponse(Status.OK, text); }
    public static LlmResponse fail(Status s) { return new LlmResponse(s, null); }

    public boolean isOk() { return status == Status.OK && text != null && !text.isEmpty(); }

    /** Worth one more attempt: the failure was transient and not caused by the request. */
    public boolean isTransient() {
        return status == Status.SERVER_ERROR || status == Status.TIMEOUT || status == Status.NETWORK;
    }
}
