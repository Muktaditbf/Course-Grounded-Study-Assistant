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

    public int coveragePercent() { return (int) Math.round(coverage * 100); }
}
