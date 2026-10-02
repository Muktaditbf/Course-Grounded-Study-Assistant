package com.seu.studyassistant.model;

public class Material {
    public long id, courseId;
    public String title, type, body;
    public boolean approved;

    /** The original upload, when there was one. Null for material typed or pasted by hand. */
    public String fileName, filePath, fileMime;

    public Material(long id, long courseId, String title, String type, String body, boolean approved) {
        this.id = id; this.courseId = courseId; this.title = title;
        this.type = type; this.body = body; this.approved = approved;
    }
    /**
     * The string resource naming this material type.
     *
     * Returning a resource id rather than a literal keeps the model free of Context while
     * still letting the label translate — the Bangla build has to show "লেকচার স্লাইড",
     * not "Lecture Slide" (SRS NFR 16.1).
     */
    /** True when the student can open the real document instead of the extracted text. */
    public boolean hasFile() {
        return filePath != null && !filePath.isEmpty();
    }

    public boolean isPdf() {
        if (!hasFile()) return false;
        if (fileMime != null && fileMime.contains("pdf")) return true;
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    public int typeLabelRes() {
        if (type == null) return com.seu.studyassistant.R.string.type_notes;
        switch (type) {
            case "lecture":    return com.seu.studyassistant.R.string.type_lecture;
            case "lab":        return com.seu.studyassistant.R.string.type_lab;
            case "assignment": return com.seu.studyassistant.R.string.type_assignment;
            case "quiz":       return com.seu.studyassistant.R.string.type_quiz;
            default:           return com.seu.studyassistant.R.string.type_notes;
        }
    }
}
