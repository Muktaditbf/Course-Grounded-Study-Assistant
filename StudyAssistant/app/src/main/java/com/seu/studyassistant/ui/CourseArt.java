package com.seu.studyassistant.ui;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.Course;

import java.util.Locale;

/**
 * Picks the illustration for a course card.
 *
 * Courses are created by teachers at runtime, so there is no fixed id to map against. The
 * subject is inferred from the title instead, most specific keyword first: "database design
 * lab" must match the database subject rather than the generic lab flask, so the subject
 * checks run before the lab fallback.
 */
public final class CourseArt {

    private CourseArt() {}

    public static int iconFor(Course c) {
        if (c == null) return R.drawable.ic_subject_book;

        String t = ((c.title == null ? "" : c.title) + " " + (c.code == null ? "" : c.code))
                .toLowerCase(Locale.ROOT);

        if (has(t, "network", "networking", "routing", "subnet")) {
            return R.drawable.ic_subject_network;
        }
        if (has(t, "database", "dbms", "sql", "data design")) {
            return R.drawable.ic_subject_database;
        }
        if (has(t, "artificial intelligence", "machine learning", "neural", " ai ")) {
            return R.drawable.ic_subject_ai;
        }
        if (has(t, "software", "system design", "programming", "algorithm", "engineering")) {
            return R.drawable.ic_subject_software;
        }
        if (has(t, "communication", "signal", "electronic", "telecom", "modulation")) {
            return R.drawable.ic_subject_signal;
        }
        // Only reached by a lab whose subject matched nothing above.
        if (has(t, "lab", "practical", "workshop")) {
            return R.drawable.ic_subject_lab;
        }
        return R.drawable.ic_subject_book;
    }

    private static boolean has(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
