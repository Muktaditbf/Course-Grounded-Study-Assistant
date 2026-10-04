package com.seu.studyassistant.model;

public class Material {
    public long id, courseId;
    public String title, type, body;
    public boolean approved;

    /** The original upload, when there was one. Null for material typed or pasted by hand. */
    public String fileName, filePath, fileMime;

    /**
     * The shared copy of the original in Firestore: how many pieces it is split into, and a
     * version that changes whenever the teacher replaces the file. filePath is only this
     * device's downloaded (or uploaded) copy, and may be null until the file is first opened.
     */
    public int fileChunks;
    public String fileVersion;

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
    /** True when there is an original document to show, on this device or in the cloud. */
    public boolean hasFile() {
        return fileName != null && !fileName.isEmpty()
                && ((filePath != null && !filePath.isEmpty()) || fileChunks > 0);
    }

    /** True when this device already holds a copy of the original. */
    public boolean hasLocalFile() {
        return filePath != null && !filePath.isEmpty() && new java.io.File(filePath).exists();
    }

    /** The original's kind, decided from its name first (MIME types from pickers vary). */
    public String fileKind() {
        String n = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        String m = fileMime == null ? "" : fileMime;
        if (n.endsWith(".pdf") || m.contains("pdf")) return "pdf";
        if (n.endsWith(".pptx") || m.contains("presentationml")) return "pptx";
        if (n.endsWith(".docx") || m.contains("wordprocessingml")) return "docx";
        return "text";
    }

    /** Text files are fully shown by their extracted text, so they need no separate viewer. */
    public boolean hasViewableOriginal() {
        return hasFile() && !"text".equals(fileKind());
    }

    public boolean isPdf() { return hasFile() && "pdf".equals(fileKind()); }

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
