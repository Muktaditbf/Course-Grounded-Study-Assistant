package com.seu.studyassistant.model;

public class User {
    public long id;
    public String name, email, contact, role, tier;

    public User(long id, String name, String email, String contact, String role, String tier) {
        this.id = id; this.name = name; this.email = email;
        this.contact = contact; this.role = role; this.tier = tier;
    }
    public boolean isTeacher() { return "teacher".equals(role); }
}
