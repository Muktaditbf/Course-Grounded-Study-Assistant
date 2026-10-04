package com.seu.studyassistant.engine.rag;

import com.seu.studyassistant.engine.AnswerResult;

/**
 * Retrieval for the AI: turns a question into ranked course passages, before any model is
 * called. USER QUERY -> RAG RETRIEVAL -> RETRIEVED CONTEXT -> LLM -> ANSWER.
 *
 * {@link AnswerResult#declined} means nothing relevant was found; {@code passages} and
 * {@code passageSources} carry the context the model may use. Blocking: call off the main thread.
 */
public interface RagRetriever {

    /** One course (the course Ask screen). */
    AnswerResult retrieveCourse(long courseId, String query);

    /** Every course the student is enrolled in (ViVi). */
    AnswerResult retrieveAll(long userId, String query);
}
