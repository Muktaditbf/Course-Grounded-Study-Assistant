package com.seu.studyassistant.model;

/** Generic list row reused by every list screen. */
public class Row {
    public long id;
    public String title, subtitle, badge, action;
    public int badgeStyle; // 0 none, 1 approved/green, 2 pending/grey, 3 amber

    /** Course illustration; 0 means none, and the row falls back to the colour stripe. */
    public int iconRes;

    public Row(long id, String title, String subtitle, String badge, int badgeStyle) {
        this.id = id; this.title = title; this.subtitle = subtitle;
        this.badge = badge; this.badgeStyle = badgeStyle;
    }
    public Row withAction(String a) { this.action = a; return this; }

    /** Course lists call this to swap the stripe for a subject illustration. */
    public Row withIcon(int iconRes) { this.iconRes = iconRes; return this; }
}
