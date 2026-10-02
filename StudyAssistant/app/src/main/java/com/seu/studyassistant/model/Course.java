package com.seu.studyassistant.model;

public class Course {
    public long id;
    public String code, title, faculty, schedule, joinCode;
    public long teacherId;

    public Course(long id, String code, String title, String faculty, String schedule, String joinCode, long teacherId) {
        this.id = id; this.code = code; this.title = title; this.faculty = faculty;
        this.schedule = schedule; this.joinCode = joinCode; this.teacherId = teacherId;
    }
}
