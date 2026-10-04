package com.seu.studyassistant.data;

/** Result of an asynchronous cloud call. Always delivered on the main thread. */
public interface Callback<T> {
    void onSuccess(T value);
    void onError(Exception error);
}
