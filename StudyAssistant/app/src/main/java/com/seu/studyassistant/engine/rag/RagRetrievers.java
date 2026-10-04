package com.seu.studyassistant.engine.rag;

import com.seu.studyassistant.BuildConfig;
import com.seu.studyassistant.data.DatabaseHelper;

/** Picks the retriever named by RAG_PROVIDER in local.properties ("local" by default). */
public final class RagRetrievers {

    private RagRetrievers() {}

    public static RagRetriever create(DatabaseHelper db) {
        RagRetriever local = new LocalRagRetriever(db);
        if ("rest".equalsIgnoreCase(BuildConfig.RAG_PROVIDER) && !BuildConfig.RAG_BASE_URL.isEmpty()) {
            return new RestRagRetriever(BuildConfig.RAG_BASE_URL, BuildConfig.RAG_API_KEY,
                    BuildConfig.RAG_AUTH_HEADER, BuildConfig.RAG_TOP_K, db, local);
        }
        return local;
    }
}
