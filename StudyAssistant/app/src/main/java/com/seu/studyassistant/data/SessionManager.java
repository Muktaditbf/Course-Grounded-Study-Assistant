package com.seu.studyassistant.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Keeps the logged in user across screens (SRS NFR 12.1: every operation needs a session),
 * plus the handful of device preferences Settings exposes.
 */
public class SessionManager {

    private static final String PREF = "session";
    private static final String KEY_USER = "userId";
    private static final String KEY_THEME = "themeMode";
    private static final String KEY_NOTIFY = "notificationsEnabled";
    private static final String KEY_RECENTS = "recentSearches";
    private static final String KEY_LANGUAGE = "language";

    /** Language tag meaning "whatever the phone is set to". Not a valid BCP-47 tag. */
    public static final String LANG_SYSTEM = "system";

    /** AppCompatDelegate's own "follow the OS" constant, named for readability. */
    public static final int MODE_FOLLOW_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;

    private static final int MAX_RECENTS = 6;
    /** Unit separator: cannot occur in anything a user can type. */
    private static final String RECENT_SEP = String.valueOf((char) 31);

    private final SharedPreferences sp;

    public SessionManager(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // -------------------------------------------------------------------- session

    /** Signing out clears the user but keeps device preferences like theme. */
    public void login(long userId) { sp.edit().putLong(KEY_USER, userId).apply(); }
    public void logout() { sp.edit().remove(KEY_USER).apply(); }

    /**
     * Drops the signed-in user without needing an instance.
     *
     * Called from DatabaseHelper.onUpgrade, which runs before any SessionManager exists and
     * must not leave a saved user id pointing at a row the rebuild just dropped.
     */
    public static void clearSession(Context c) {
        c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY_USER).apply();
    }
    public long userId() { return sp.getLong(KEY_USER, -1); }
    public boolean isLoggedIn() { return userId() > 0; }

    // ---------------------------------------------------------------- preferences

    /**
     * The chosen UI language as a BCP-47 tag ("en", "bn"), or LANG_SYSTEM to follow the phone.
     *
     * Persisted here rather than relying on the platform because per-app languages are only
     * stored by the system from Android 13; below that AppCompat needs the value handed back
     * to it on every cold start.
     */
    public String language() { return sp.getString(KEY_LANGUAGE, LANG_SYSTEM); }
    public void setLanguage(String tag) { sp.edit().putString(KEY_LANGUAGE, tag).apply(); }

    public int themeMode() { return sp.getInt(KEY_THEME, MODE_FOLLOW_SYSTEM); }
    public void setThemeMode(int mode) { sp.edit().putInt(KEY_THEME, mode).apply(); }

    public boolean notificationsEnabled() { return sp.getBoolean(KEY_NOTIFY, true); }
    public void setNotificationsEnabled(boolean on) {
        sp.edit().putBoolean(KEY_NOTIFY, on).apply();
    }

    // ------------------------------------------------------------ recent searches

    /** Most recent first, de-duplicated, capped so the chip row cannot overflow. */
    public String[] recentSearches() {
        String raw = sp.getString(KEY_RECENTS, "");
        if (raw == null || raw.isEmpty()) return new String[0];
        return raw.split(RECENT_SEP);
    }

    public void addRecentSearch(String query) {
        if (query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;

        StringBuilder out = new StringBuilder(q);
        int kept = 1;
        for (String old : recentSearches()) {
            if (kept >= MAX_RECENTS) break;
            if (old.equalsIgnoreCase(q)) continue;
            out.append(RECENT_SEP).append(old);
            kept++;
        }
        sp.edit().putString(KEY_RECENTS, out.toString()).apply();
    }

    public void clearRecentSearches() { sp.edit().remove(KEY_RECENTS).apply(); }

    // ------------------------------------------------------- notification badge

    /** Highest notification id the user has already seen, per user. */
    public long lastSeenNotification(long userId) {
        return sp.getLong("seen_notif_" + userId, 0);
    }

    public void setLastSeenNotification(long userId, long notificationId) {
        sp.edit().putLong("seen_notif_" + userId, notificationId).apply();
    }
}
