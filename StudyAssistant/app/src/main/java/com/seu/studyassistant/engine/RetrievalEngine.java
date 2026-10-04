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
 * Scoring is Okapi BM25 over passages, the standard lexical ranking function:
 *   idf(t)   = ln(1 + (N - df + 0.5) / (df + 0.5))
 *   score(p) = sum over query terms of idf(t) * tf * (k1 + 1) / (tf + k1 * (1 - b + b * |p| / avg))
 * plus a bonus when two query words appear next to each other in the passage (so "binary
 * search tree" prefers a passage with that phrase over one with the three words scattered),
 * and the material's title counts as part of each of its passages.
 *
 *   coverage = distinct query terms found in the top passages / distinct query terms
 *
 * Tokens are Unicode letters and digits, so Bangla questions and material are indexed too.
 */
public class RetrievalEngine {

    /** SRS NFR 14.1 makes the threshold mandatory but never fixes a number; this is ours. */
    public static final double COVERAGE_THRESHOLD = 0.34;

    private static final int MAX_ANSWER_PASSAGES = 3;

    /** How many ranked passages the AI receives as its only allowed source. */
    private static final int MAX_CONTEXT_PASSAGES = 5;

    /** BM25 parameters: k1 controls term-frequency saturation, b length normalisation. */
    private static final double K1 = 1.2, B = 0.75;
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

    /**
     * Tokenised passages from the last load, reused while the library is unchanged. Tokenising
     * every passage again for every question was the slowest step of asking; the version
     * counter in DatabaseHelper says when the cache is stale.
     */
    private static final Map<String, List<Passage>> CACHE = new HashMap<>();
    private static long cacheVersion = -1;

    private static synchronized List<Passage> cached(String key) {
        if (cacheVersion != DatabaseHelper.materialsVersion()) {
            CACHE.clear();
            cacheVersion = DatabaseHelper.materialsVersion();
        }
        List<Passage> l = CACHE.get(key);
        if (l == null) return null;
        // Scores are written onto the passages, so each question gets its own copies.
        List<Passage> copy = new ArrayList<>(l.size());
        for (Passage p : l) copy.add(p.copy());
        return copy;
    }

    private static synchronized void store(String key, List<Passage> list, long version) {
        if (version != DatabaseHelper.materialsVersion()) return;   // changed while loading
        List<Passage> copy = new ArrayList<>(list.size());
        for (Passage p : list) copy.add(p.copy());
        CACHE.put(key, copy);
    }

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

        String key = courseId > 0 ? "c" + courseId : "u" + userId;
        List<Passage> passages = cached(key);
        if (passages == null) {
            long version = DatabaseHelper.materialsVersion();
            passages = courseId > 0 ? loadApprovedPassages(courseId) : loadApprovedPassagesForUser(userId);
            store(key, passages, version);
        }
        if (passages.isEmpty()) {
            // Content Lock with nothing approved yet: there is nothing legal to answer from.
            result.declined = true;
            result.coverage = 0;
            return result;
        }

        Map<String, Integer> docFreq = new HashMap<>();
        double totalLen = 0;
        for (Passage p : passages) {
            totalLen += p.terms.size();
            for (String t : new HashSet<>(p.terms)) {
                Integer n = docFreq.get(t);
                docFreq.put(t, n == null ? 1 : n + 1);
            }
        }
        int total = passages.size();
        double avgLen = Math.max(1, totalLen / total);
        List<String> queryList = new ArrayList<>(distinctQuery);

        for (Passage p : passages) {
            Map<String, Integer> tf = new HashMap<>();
            for (String t : p.terms) {
                Integer n = tf.get(t);
                tf.put(t, n == null ? 1 : n + 1);
            }
            double norm = K1 * (1 - B + B * p.terms.size() / avgLen);
            double score = 0, idfSum = 0;
            int matched = 0;
            for (String q : distinctQuery) {
                Integer df = docFreq.get(q);
                double idf = Math.log(1 + (total - (df == null ? 0 : df) + 0.5) / ((df == null ? 0 : df) + 0.5));
                idfSum += idf;
                Integer raw = tf.get(q);
                if (raw == null) continue;
                matched++;
                score += idf * raw * (K1 + 1) / (raw + norm);
            }
            // Phrase bonus: adjacent query words found adjacent in the passage.
            if (queryList.size() > 1 && score > 0) {
                Set<String> bigrams = new HashSet<>();
                for (int i = 0; i + 1 < p.terms.size(); i++) bigrams.add(p.terms.get(i) + " " + p.terms.get(i + 1));
                int phrases = 0;
                for (int i = 0; i + 1 < queryTerms.size(); i++) {
                    if (bigrams.contains(queryTerms.get(i) + " " + queryTerms.get(i + 1))) phrases++;
                }
                score += phrases * 0.5 * (idfSum / distinctQuery.size());
            }
            p.score = score * typeWeight(p.type);
            p.coverage = (double) matched / distinctQuery.size();
        }

        Collections.sort(passages, new Comparator<Passage>() {
            @Override public int compare(Passage a, Passage b) {
                return Double.compare(b.score, a.score);
            }
        });

        Passage best = passages.get(0);

        // Coverage over the top few passages together: a two-part question is often answered by
        // two neighbouring passages, and neither alone covers both halves.
        Set<String> covered = new HashSet<>();
        for (int i = 0; i < Math.min(3, passages.size()); i++) {
            if (passages.get(i).score <= 0) break;
            Set<String> terms = new HashSet<>(passages.get(i).terms);
            for (String q : distinctQuery) if (terms.contains(q)) covered.add(q);
        }
        result.coverage = (double) covered.size() / distinctQuery.size();

        // ---- SRS NFR 14.1: decline rather than guess.
        if (result.coverage < COVERAGE_THRESHOLD || best.score <= 0) {
            result.declined = true;
            return result;
        }

        // ---- The AI's context: the best passages, ranked, each with its source material.
        for (Passage p : passages) {
            if (result.passages.size() >= MAX_CONTEXT_PASSAGES || p.score <= 0) break;
            if (p.score < best.score * 0.35) break;   // far weaker matches only add noise
            Material m = db.materialById(p.materialId);
            if (m == null) continue;
            result.passages.add(p.text);
            result.passageSources.add(m);
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
                "SELECT ch.material_id, ch.text, m.type, m.title FROM chunks ch " +
                "JOIN materials m ON m.id = ch.material_id " +
                "JOIN enrollments e ON e.course_id = ch.course_id " +
                "WHERE e.user_uid = ? AND m.approved = 1",
                // Enrolments are keyed by Firebase uid since the cloud-sync schema; querying
                // the old local user_id column crashed every ViVi question.
                new String[]{String.valueOf(db.uidOf(userId))});
        while (c.moveToNext()) {
            Passage p = new Passage();
            p.materialId = c.getLong(0);
            p.text = c.getString(1);
            p.type = c.getString(2);
            // The title is part of every passage's terms: "Lecture 4: Software Testing" tells
            // retrieval what a passage is about even when the passage never repeats it.
            p.terms = tokenize(c.getString(3) + " " + p.text);
            list.add(p);
        }
        c.close();
        return list;
    }

    private List<Passage> loadApprovedPassages(long courseId) {
        List<Passage> list = new ArrayList<>();
        SQLiteDatabase sdb = db.getReadableDatabase();
        Cursor c = sdb.rawQuery(
                "SELECT ch.material_id, ch.text, m.type, m.title FROM chunks ch " +
                "JOIN materials m ON m.id = ch.material_id " +
                "WHERE ch.course_id = ? AND m.approved = 1",
                new String[]{String.valueOf(courseId)});
        while (c.moveToNext()) {
            Passage p = new Passage();
            p.materialId = c.getLong(0);
            p.text = c.getString(1);
            p.type = c.getString(2);
            // The title is part of every passage's terms: "Lecture 4: Software Testing" tells
            // retrieval what a passage is about even when the passage never repeats it.
            p.terms = tokenize(c.getString(3) + " " + p.text);
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
        for (String raw : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (raw.isEmpty()) continue;
            boolean ascii = raw.chars().allMatch(ch -> ch < 128);
            if (ascii) {
                if (raw.length() < 3 && !isNumber(raw)) continue;
                if (STOP.contains(raw)) continue;
                out.add(stem(raw));
            } else {
                // Bangla and other scripts: kept whole (no English stemming), short words too,
                // because many meaningful Bangla words are two letters long.
                if (raw.length() < 2 || BN_STOP.contains(raw)) continue;
                out.add(raw);
            }
        }
        return out;
    }

    private static boolean isNumber(String s) {
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** Common Bangla function words, which carry no topic. */
    private static final Set<String> BN_STOP = new HashSet<>(Arrays.asList(
            "এবং", "ও", "কি", "কী", "কেন", "কিভাবে", "কীভাবে", "এর", "এই", "সেই", "একটি", "হয়",
            "হলো", "করে", "করা", "থেকে", "জন্য", "দিয়ে", "সাথে", "মধ্যে", "যে", "তা", "আর", "বা",
            "না", "নয়", "হবে", "ছিল", "আছে", "বলুন", "ব্যাখ্যা", "লিখুন"));

    /**
     * A light English suffix stripper so word forms meet at one root: "normalization",
     * "normalize", "normalized" and "normalizing" all become "normaliz". Deliberately
     * conservative - over-stemming merges unrelated words and hurts precision more than it helps.
     */
    static String stem(String w) {
        if (w.length() <= 3) return w;
        String[][] rules = {
                {"ational", "ate"}, {"ization", "iz"}, {"isation", "iz"}, {"fulness", "ful"},
                {"iveness", "ive"}, {"ousness", "ous"}, {"ations", "ate"}, {"ation", "ate"},
                {"ments", ""}, {"ment", ""}, {"ities", "ity"}, {"izing", "iz"}, {"ising", "iz"},
                {"ized", "iz"}, {"ised", "iz"}, {"izes", "iz"}, {"ises", "iz"}, {"ize", "iz"}, {"ise", "iz"},
                {"ies", "y"}, {"ing", ""}, {"edly", ""}, {"ed", ""}, {"es", ""}, {"ly", ""}, {"s", ""}};
        for (String[] r : rules) {
            if (w.endsWith(r[0]) && w.length() - r[0].length() >= 3) {
                String base = w.substring(0, w.length() - r[0].length()) + r[1];
                // "ss" endings (class, process) are not plurals.
                if (r[0].equals("s") && w.endsWith("ss")) return w;
                // Undouble a final consonant left by -ing/-ed: "mapping" -> "map".
                if ((r[0].equals("ing") || r[0].equals("ed")) && base.length() > 3
                        && base.charAt(base.length() - 1) == base.charAt(base.length() - 2)
                        && "aeiouls".indexOf(base.charAt(base.length() - 1)) < 0) {
                    base = base.substring(0, base.length() - 1);
                }
                return dropFinalE(base);
            }
        }
        return dropFinalE(w);
    }

    /** "database" and "databases" (stripped to "databas") must meet; so must "phase"/"phases". */
    private static String dropFinalE(String w) {
        return w.length() > 4 && w.endsWith("e") ? w.substring(0, w.length() - 1) : w;
    }

    private static class Passage {
        long materialId;
        String text;
        String type;
        List<String> terms;
        double score;
        double coverage;

        Passage copy() {
            Passage p = new Passage();
            p.materialId = materialId; p.text = text; p.type = type; p.terms = terms;
            return p;
        }
    }

    private static class Scored {
        final Material material;
        final int overlap;
        Scored(Material m, int o) { material = m; overlap = o; }
    }
}
