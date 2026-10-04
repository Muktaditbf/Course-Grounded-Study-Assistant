package com.seu.studyassistant.engine;

import com.seu.studyassistant.data.DatabaseHelper;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.User;

import java.util.List;

/**
 * Facts about the signed-in student and their courses, written out for the AI.
 *
 * Retrieval finds passages *inside* materials; this covers everything around them that a
 * student naturally asks about - who teaches a course, when it meets, which lectures and labs
 * it has, their own name and plan. Only approved materials are listed (the Content Lock), and
 * the phone number is left out: the assistant has no use for it and it would leave the device.
 */
public final class StudyContext {

    /** Keeps the prompt small enough for free-tier token limits on large courses. */
    private static final int MAX_MATERIALS_PER_COURSE = 40;

    private StudyContext() {}

    /** The student plus every enrolled course. Blocking database reads: call off the main thread. */
    public static String forStudent(DatabaseHelper db, long userId) {
        User u = db.userById(userId);
        StringBuilder b = new StringBuilder();
        if (u != null) {
            b.append("Signed-in user:\n")
             .append("- Name: ").append(orDash(u.name)).append('\n')
             .append("- Email: ").append(orDash(u.email)).append('\n')
             .append("- Role: ").append(orDash(u.role)).append('\n')
             .append("- Plan: ").append(orDash(u.tier)).append("\n\n");
        }
        List<Course> courses = db.coursesForStudent(userId);
        if (courses.isEmpty()) {
            b.append("The student is not enrolled in any course yet.\n");
            return b.toString();
        }
        b.append("Enrolled courses (").append(courses.size()).append("):\n");
        for (Course c : courses) appendCourse(db, c, b);
        return b.toString();
    }

    /** One course, for the course Ask screen. */
    public static String forCourse(DatabaseHelper db, long courseId) {
        Course c = db.courseById(courseId);
        if (c == null) return "";
        StringBuilder b = new StringBuilder("This course:\n");
        appendCourse(db, c, b);
        return b.toString();
    }

    private static void appendCourse(DatabaseHelper db, Course c, StringBuilder b) {
        b.append("* ").append(orDash(c.code)).append(" - ").append(orDash(c.title)).append('\n')
         .append("  Teacher: ").append(orDash(c.faculty)).append('\n')
         .append("  Schedule: ").append(orDash(c.schedule)).append('\n')
         .append("  Join code: ").append(orDash(c.joinCode)).append('\n');

        List<Material> materials = db.materials(c.id, true);
        if (materials.isEmpty()) {
            b.append("  Approved materials: none yet\n");
            return;
        }
        b.append("  Approved materials (").append(materials.size()).append("):\n");
        int shown = 0;
        for (Material m : materials) {
            if (shown++ >= MAX_MATERIALS_PER_COURSE) {
                b.append("    ...and ").append(materials.size() - MAX_MATERIALS_PER_COURSE).append(" more\n");
                break;
            }
            b.append("    - ").append(m.title).append(" (").append(m.type);
            if (m.hasViewableOriginal()) b.append(", ").append(m.fileKind().toUpperCase(java.util.Locale.ROOT)).append(" file");
            b.append(")\n");
        }
    }

    private static String orDash(String s) {
        return s == null || s.trim().isEmpty() ? "not set" : s.trim();
    }
}
