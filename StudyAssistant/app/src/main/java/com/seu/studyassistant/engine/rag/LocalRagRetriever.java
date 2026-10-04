package com.seu.studyassistant.engine.rag;

import com.seu.studyassistant.data.DatabaseHelper;
import com.seu.studyassistant.engine.AnswerResult;
import com.seu.studyassistant.engine.RetrievalEngine;

/**
 * The built-in retriever: BM25 over teacher-approved material synced to the device. Needs no
 * key or network, and only ever sees approved material (the Content Lock).
 */
public final class LocalRagRetriever implements RagRetriever {

    private final RetrievalEngine engine;

    public LocalRagRetriever(DatabaseHelper db) { engine = new RetrievalEngine(db); }

    @Override public AnswerResult retrieveCourse(long courseId, String query) {
        return engine.ask(courseId, query);
    }

    @Override public AnswerResult retrieveAll(long userId, String query) {
        return engine.askEverything(userId, query);
    }
}
