package com.seu.studyassistant.engine;

import com.seu.studyassistant.model.Material;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one grounded question (SRS UC5 main scenario and alternative course 3.a). */
public class AnswerResult {
    public boolean declined;
    public String answer = "";
    public double coverage;
    public List<Material> sources = new ArrayList<>();
    public List<Material> related = new ArrayList<>();

    /**
     * The best passages, in rank order, with the material each came from (same index). These
     * are what the AI is allowed to answer from, numbered [1], [2]... in its instructions.
     */
    public List<String> passages = new ArrayList<>();
    public List<Material> passageSources = new ArrayList<>();

    public int coveragePercent() { return (int) Math.round(coverage * 100); }
}
