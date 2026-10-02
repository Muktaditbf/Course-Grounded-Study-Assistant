package com.seu.studyassistant.engine;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.seu.studyassistant.data.DatabaseHelper;
import com.seu.studyassistant.model.Material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On device retrieval, grounded strictly in teacher approved material.
 *
 * Implements:
 *   PDD 6.1 / SRS FR 3.2 - Teacher-Enforced Content Lock. Only chunks whose parent
 *                          material has approved = 1 are ever scored. Unapproved
 *                          material is invisible: it cannot influence an answer.
 *   SRS NFR 14.1 / UC5 3.a - Coverage threshold. When the best passage covers less
 *                          than COVERAGE_THRESHOLD of the meaningful question terms,
 *                          the system declines instead of guessing.
 *   PDD 6.2 / SRS UC9    - Lecture Connection Finder. After a successful answer, other
 *                          materials in the same course are ranked by term overlap and
 *                          returned grouped by material type.
 *
 * Scoring is TF-IDF over passages:
 *   idf(t)   = ln(1 + N / df(t))            N = number of approved chunks in the course
 *   tf(t,c)  = 1 + ln(raw count of t in c)
 *   score(c) = sum over matched query terms of tf(t,c) * idf(t) / sqrt(length of c)
 *   coverage = matched distinct query terms / total distinct query terms
 */
public class RetrievalEngine {

    /** SRS NFR 14.1 makes the threshold mandatory but never fixes a number; this is ours. */
    public static final double COVERAGE_THRESHOLD = 0.34;

    private static final int MAX_ANSWER_PASSAGES = 3;
    private static final int MAX_RELATED = 6;

    /** A passage sharing more than this fraction of its wording with the answer is redundant. */
    private static final double MAX_PASSAGE_OVERLAP = 0.4;

    /** A supporting passage must score at least this fraction of the best passage. */
    private static final double MIN_RELATIVE_SCORE = 0.8;

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "her", "was", "one",
            "our", "out", "day", "get", "has", "him", "his", "how", "man", "new", "now", "old",
            "see", "two", "way", "who", "boy", "did", "its", "let", "put", "say", "she", "too",
            "use", "that", "with", "have", "this", "will", "your", "from", "they", "know", "want",
            "been", "good", "much", "some", "time", "very", "when", "come", "here", "just", "like",
            "long", "make", "many", "over", "such", "take", "than", "them", "well", "were", "what",
            "which", "their", "would", "there", "about", "could", "other", "into", "does", "why",
            "explain", "define", "describe", "list", "state", "give", "tell", "write", "between",
            "difference", "differences", "meaning", "means", "example", "examples"));

    private final DatabaseHelper db;

    public RetrievalEngine(DatabaseHelper db) { this.db = db; }

    // ------------------------------------------------------------- public API

    /**
     * Retrieves across every course the student is enrolled in.
     *
     * This is what the chat assistant uses. Asking inside one course means the student has
     * already decided where the answer lives; the chat is for when they have not, so it
     * searches the whole approved library at once. The Content Lock is unchanged - the query
     * still joins approved = 1, and still only reaches courses this user is enrolled in.
     */
    public AnswerResult askEverything(long userId, String question) {
        return ask(-1, userId, question);
    }

    public AnswerResult ask(long courseId, String question) {
        return ask(courseId, -1, question);
    }

    private AnswerResult ask(long courseId, long userId, String question) {
        AnswerResult result = new AnswerResult();

        List<String> queryTerms = tokenize(question);
        Set<String> distinctQuery = new LinkedHashSet<>(queryTerms);
        if (distinctQuery.isEmpty()) {
            result.declined = true;
            result.coverage = 0;
            return result;
        }

        List<Passage> passages = courseId > 0
                ? loadApprovedPassages(courseId)
                : loadApprovedPassagesForUser(userId);
        if (passages.isEmpty()) {
            // Content Lock with nothing approved yet: there is nothing legal to answer from.
            result.declined = true;
            result.coverage = 0;
            return result;
        }

        Map<String, Integer> docFreq = new HashMap<>();
        for (Passage p : passages) {
            for (String t : new HashSet<>(p.terms)) {
                Integer n = docFreq.get(t);
                docFreq.put(t, n == null ? 1 : n + 1);
            }
        }
        int total = passages.size();

        for (Passage p : passages) {
            Map<String, Integer> tf = new HashMap<>();
            for (String t : p.terms) {
                Integer n = tf.get(t);
                tf.put(t, n == null ? 1 : n + 1);
            }
            double score = 0;
            int matched = 0;
            for (String q : distinctQuery) {
                Integer raw = tf.get(q);
                if (raw == null) continue;
                matched++;
                Integer df = docFreq.get(q);
                double idf = Math.log(1.0 + (double) total / (df == null ? 1 : df));
                score += (1 + Math.log(raw)) * idf;
            }
            p.score = score / Math.sqrt(Math.max(1, p.terms.size()));
            p.score *= typeWeight(p.type);
            p.coverage = (double) matched / distinctQuery.size();
        }

        Collections.sort(passages, new Comparator<Passage>() {
            @Override public int compare(Passage a, Passage b) {
                return Double.compare(b.score, a.score);
            }
        });

        Passage best = passages.get(0);
        result.coverage = best.coverage;

        // ---- SRS NFR 14.1: decline rather than guess.
        if (best.coverage < COVERAGE_THRESHOLD || best.score <= 0) {
            result.declined = true;
            return result;
        }

        // ---- Build the grounded answer from the top passages only.
        //
        // Chunks are consecutive and non-overlapping, but two passages can still restate the
        // same idea. Skip any whose wording is largely present in the answer already.
        StringBuilder answer = new StringBuilder();
        Set<Long> usedMaterials = new LinkedHashSet<>();
        Set<String> usedTerms = new HashSet<>();
        int taken = 0;
        for (Passage p : passages) {
            if (taken >= MAX_ANSWER_PASSAGES) break;
            if (p.coverage < COVERAGE_THRESHOLD * 0.5) continue;

            // Relevance drop-off: once a passage scores well below the best match it is only
            // loosely on topic (e.g. a quiz that merely mentions the term) and would dilute
            // the answer. Keep the answer tight rather than long.
            if (p.score < best.score * MIN_RELATIVE_SCORE) continue;

            if (!usedTerms.isEmpty()) {
                Set<String> distinct = new HashSet<>(p.terms);
                if (!distinct.isEmpty()) {
                    int repeated = 0;
                    for (String t : distinct) if (usedTerms.contains(t)) repeated++;
                    if ((double) repeated / distinct.size() > MAX_PASSAGE_OVERLAP) continue;
                }
            }

            if (answer.length() > 0) answer.append("\n\n");
            answer.append(p.text);
            usedTerms.addAll(p.terms);
            usedMaterials.add(p.materialId);
            taken++;
        }
        result.answer = answer.toString();

        for (Long id : usedMaterials) {
            Material m = db.materialById(id);
            if (m != null) result.sources.add(m);
        }

        result.related = courseId > 0
                ? findRelated(courseId, distinctQuery, usedMaterials)
                : new ArrayList<Material>();
        return result;
    }

    /**
     * PDD 6.2 / UC9 - Lecture Connection Finder. Ranks every other approved material in the
     * same course by overlap with the question, then interleaves by type so the student sees
     * a lecture, a lab, an assignment and a quiz together rather than four of one kind.
     */
    private List<Material> findRelated(long courseId, Set<String> queryTerms, Set<Long> exclude) {
        List<Material> all = db.materials(courseId, true);
        List<Scored> scored = new ArrayList<>();

        for (Material m : all) {
            if (exclude.contains(m.id)) continue;
            Set<String> terms = new HashSet<>(tokenize(m.title + " " + m.body));
            int overlap = 0;
            for (String q : queryTerms) if (terms.contains(q)) overlap++;
            if (overlap > 0) scored.add(new Scored(m, overlap));
        }

        Collections.sort(scored, new Comparator<Scored>() {
            @Override public int compare(Scored a, Scored b) {
                return Integer.compare(b.overlap, a.overlap);
            }
        });

        // Interleave by material type so the connection spans lecture, lab, assignment, quiz.
        List<Material> out = new ArrayList<>();
        Set<String> typesUsed = new HashSet<>();
        for (Scored s : scored) {
            if (out.size() >= MAX_RELATED) break;
            if (typesUsed.add(s.material.type)) out.add(s.material);
        }
        for (Scored s : scored) {
            if (out.size() >= MAX_RELATED) break;
            if (!out.contains(s.material)) out.add(s.material);
        }
        return out;
    }

    // -------------------------------------------------------------- internals

    /** Loads only passages belonging to approved materials. This IS the Content Lock. */
    /** Every approved passage from every course this student is enrolled in. */
    private List<Passage> loadApprovedPassagesForUser(long userId) {
        List<Passage> list = new ArrayList<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT ch.material_id, ch.text, m.type FROM chunks ch " +
                "JOIN materials m ON m.id = ch.material_id " +
                "JOIN enrollments e ON e.course_id = ch.course_id " +
                "WHERE e.user_id = ? AND m.approved = 1",
                new String[]{String.valueOf(userId)});
        while (c.moveToNext()) {
            Passage p = new Passage();
            p.materialId = c.getLong(0);
            p.text = c.getString(1);
            p.type = c.getString(2);
            p.terms = tokenize(p.text);
            list.add(p);
        }
        c.close();
        return list;
    }

    private List<Passage> loadApprovedPassages(long courseId) {
        List<Passage> list = new ArrayList<>();
        SQLiteDatabase sdb = db.getReadableDatabase();
        Cursor c = sdb.rawQuery(
                "SELECT ch.material_id, ch.text, m.type FROM chunks ch " +
                "JOIN materials m ON m.id = ch.material_id " +
                "WHERE ch.course_id = ? AND m.approved = 1",
                new String[]{String.valueOf(courseId)});
        while (c.moveToNext()) {
            Passage p = new Passage();
            p.materialId = c.getLong(0);
            p.text = c.getString(1);
            p.type = c.getString(2);
            p.terms = tokenize(p.text);
            list.add(p);
        }
        c.close();
        return list;
    }

    /**
     * Explanatory material outranks assessment material.
     *
     * A quiz that asks "list the phases of the waterfall model" matches those words perfectly
     * but answers nothing, so without this it would outrank the lecture that actually explains
     * the topic. Quizzes and assignments still surface under Related Resources (UC9).
     */
    private static double typeWeight(String type) {
        if (type == null) return 1.0;
        switch (type) {
            case "quiz":       return 0.60;
            case "assignment": return 0.80;
            case "lab":        return 0.95;
            default:           return 1.00;   // lecture, notes
        }
    }

    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() < 3) continue;
            if (STOP.contains(raw)) continue;
            out.add(stem(raw));
        }
        return out;
    }

    /** Very small suffix stripper so plurals and gerunds match their root form. */
    private static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && w.endsWith("es")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static class Passage {
        long materialId;
        String text;
        String type;
        List<String> terms;
        double score;
        double coverage;
    }

    private static class Scored {
        final Material material;
        final int overlap;
        Scored(Material m, int o) { material = m; overlap = o; }
    }
}
