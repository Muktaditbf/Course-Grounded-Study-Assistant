package com.seu.studyassistant.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Mirrors the signed-in user's slice of Firestore into the local SQLite database.
 *
 * What a user may read is decided by firestore.rules; the queries below are written to match
 * those rules exactly (a query the rules cannot prove safe is rejected outright), which is why
 * a student reads materials one enrolled course at a time with approved == true, and a teacher
 * reads by teacherUid.
 *
 * This is where the Content Lock becomes shared: when a teacher revokes a material, the
 * student's approved == true query stops matching it, Firestore reports the document REMOVED,
 * and it is deleted from the student's mirror - so retrieval can no longer see it.
 */
public final class SyncManager {

    private static final String TAG = "SyncManager";

    private static final SyncManager INSTANCE = new SyncManager();
    public static SyncManager get() { return INSTANCE; }

    private SyncManager() {}

    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Snapshots are applied on this single background thread. Re-indexing a long lecture on the
     * main thread made the first sync stutter; one thread also keeps changes in arrival order.
     */
    private final Executor mirror = Executors.newSingleThreadExecutor();
    private final List<ListenerRegistration> regs = new ArrayList<>();
    /** Per enrolled course (student only): its document listener and its materials listener. */
    private final Map<String, List<ListenerRegistration>> perCourse = new HashMap<>();
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ErrorListener> errorListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> courseRetries = new HashMap<>();

    /** Receives sync and write failures so the UI can say so instead of failing silently. */
    public interface ErrorListener { void onSyncError(String message); }

    private DatabaseHelper db;
    private String uid;
    private boolean teacher;
    private boolean coursesSynced;

    private final Runnable fire = new Runnable() {
        @Override public void run() {
            for (Runnable r : changeListeners) r.run();
        }
    };

    // ------------------------------------------------------------------- lifecycle

    public synchronized boolean isRunningFor(String forUid) {
        return uid != null && uid.equals(forUid);
    }

    /** Idempotent: calling it again for the same user does nothing. */
    public synchronized void start(Context ctx, String forUid, String role) {
        if (isRunningFor(forUid)) return;
        stop();

        db = DatabaseHelper.get(ctx);
        uid = forUid;
        teacher = "teacher".equals(role);
        coursesSynced = false;

        FirebaseFirestore fs = FirebaseFirestore.getInstance();

        regs.add(fs.collection("users").document(uid).addSnapshotListener(mirror, (doc, e) -> {
            if (doc == null || !doc.exists()) return;
            db.updateUserProfile(uid, doc.getString("name"), doc.getString("role"),
                    doc.getString("tier"));
            changed();
        }));

        regs.add(listen(fs.collection("notifications").whereEqualTo("userUid", uid), "notifications"));

        if (teacher) startTeacher(fs); else startStudent(fs);
    }

    public synchronized void stop() {
        for (ListenerRegistration r : regs) r.remove();
        regs.clear();
        for (List<ListenerRegistration> l : perCourse.values()) {
            for (ListenerRegistration r : l) r.remove();
        }
        perCourse.clear();
        courseRetries.clear();
        uid = null;
        coursesSynced = false;
    }

    /** True once the first snapshot of the teacher's own courses has been applied. */
    public boolean isCourseListReady() { return coursesSynced; }

    // ------------------------------------------------------------ change callbacks

    public void addChangeListener(Runnable r) { changeListeners.addIfAbsent(r); }
    public void removeChangeListener(Runnable r) { changeListeners.remove(r); }

    public void addErrorListener(ErrorListener l) { errorListeners.addIfAbsent(l); }
    public void removeErrorListener(ErrorListener l) { errorListeners.remove(l); }

    /** Called from any thread; delivered on the main thread. */
    public void reportError(final String message) {
        main.post(new Runnable() {
            @Override public void run() {
                for (ErrorListener l : errorListeners) l.onSyncError(message);
            }
        });
    }

    /** Coalesces bursts of snapshots into one UI refresh. */
    private void changed() {
        main.removeCallbacks(fire);
        main.postDelayed(fire, 250);
    }

    // --------------------------------------------------------------------- teacher

    private void startTeacher(FirebaseFirestore fs) {
        regs.add(listen(fs.collection("courses").whereEqualTo("teacherUid", uid), "courses"));
        regs.add(listen(fs.collection("materials").whereEqualTo("teacherUid", uid), "materials"));
        regs.add(listen(fs.collection("enrollments").whereEqualTo("teacherUid", uid), "enrollments"));
        regs.add(listen(fs.collection("questions").whereEqualTo("teacherUid", uid), "questions"));
    }

    // --------------------------------------------------------------------- student

    private void startStudent(FirebaseFirestore fs) {
        regs.add(listen(fs.collection("questions").whereEqualTo("userUid", uid), "questions"));
        regs.add(listen(fs.collection("bookmarks").whereEqualTo("userUid", uid), "bookmarks"));
        regs.add(listen(fs.collection("enrollments").whereEqualTo("userUid", uid), "enrollments"));
    }

    /** Starts the per-course listeners once the student is enrolled in that course. */
    private synchronized void startCourse(String courseId) {
        if (perCourse.containsKey(courseId)) return;
        FirebaseFirestore fs = FirebaseFirestore.getInstance();

        List<ListenerRegistration> l = new ArrayList<>();
        l.add(fs.collection("courses").document(courseId).addSnapshotListener(mirror, (doc, e) -> {
            if (e != null) {
                Log.w(TAG, "course listener: " + e.getMessage());
                retryCourse(courseId);
                return;
            }
            if (doc == null) return;
            if (!doc.exists()) {
                // The teacher deleted the course: drop it and everything under it locally.
                db.deleteCourseLocal(courseId);
                stopCourse(courseId);
                changed();
                return;
            }
            courseRetries.remove(courseId);
            applyCourse(doc);
            changed();
        }));
        // approved == true is part of the query on purpose: the rules only allow a student to
        // read approved documents, and revoking a material turns into a REMOVED change here.
        l.add(listen(fs.collection("materials")
                .whereEqualTo("courseId", courseId)
                .whereEqualTo("approved", true), "materials"));
        perCourse.put(courseId, l);
    }

    /**
     * A freshly joined course can be listened to a moment before the server has applied the
     * enrolment that the rules check, which fails the listener with permission denied and
     * cancels it for good. Starting it again shortly afterwards succeeds.
     */
    private synchronized void retryCourse(final String courseId) {
        Integer n = courseRetries.get(courseId);
        int attempt = n == null ? 0 : n;
        if (attempt >= 5) {
            reportError("Could not load course data (permission denied by Firestore rules)");
            return;
        }
        courseRetries.put(courseId, attempt + 1);
        stopCourse(courseId);
        final String forUid = uid;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (SyncManager.this) {
                    if (forUid != null && forUid.equals(uid) && !teacher) startCourse(courseId);
                }
            }
        }, 1500L * (attempt + 1));
    }

    private synchronized void stopCourse(String courseId) {
        List<ListenerRegistration> l = perCourse.remove(courseId);
        if (l == null) return;
        for (ListenerRegistration r : l) r.remove();
    }

    // ------------------------------------------------------------------ the mirror

    private ListenerRegistration listen(Query q, final String collection) {
        return q.addSnapshotListener(mirror, (snap, e) -> {
            if (e != null) {
                // Typically PERMISSION_DENIED from a query the rules reject, or being offline
                // with nothing cached. The mirror simply keeps what it has.
                Log.w(TAG, collection + " listener: " + e.getMessage());
                reportError(collection + ": " + e.getMessage());
                return;
            }
            if (snap == null) return;
            apply(collection, snap);
        });
    }

    private void apply(final String collection, final QuerySnapshot snap) {
        if (db == null || uid == null) return;   // a snapshot that raced sign-out
        db.inTransaction(() -> {
            for (DocumentChange ch : snap.getDocumentChanges()) {
                QueryDocumentSnapshot doc = ch.getDocument();
                boolean removed = ch.getType() == DocumentChange.Type.REMOVED;
                try {
                    applyOne(collection, doc, removed);
                } catch (RuntimeException ex) {
                    // One malformed document must not stop the rest of the snapshot.
                    Log.w(TAG, "skipped " + collection + "/" + doc.getId() + ": " + ex.getMessage());
                }
            }
        });
        if ("courses".equals(collection)) coursesSynced = true;
        changed();
    }

    private void applyOne(String collection, DocumentSnapshot doc, boolean removed) {
        String id = doc.getId();
        switch (collection) {
            case "courses":
                if (removed) db.deleteCourseLocal(id);
                else applyCourse(doc);
                break;

            case "enrollments": {
                String courseId = doc.getString("courseId");
                if (removed) {
                    db.deleteByRemote("enrollments", id);
                    if (!teacher && courseId != null) {
                        stopCourse(courseId);
                        db.deleteCourseLocal(courseId);
                    }
                } else if (courseId != null) {
                    db.upsertEnrollment(id, str(doc.getString("userUid")), courseId);
                    if (!teacher) startCourse(courseId);
                }
                break;
            }

            case "materials":
                if (removed) {
                    db.deleteByRemote("materials", id);
                } else if (doc.getString("courseId") != null) {
                    Long chunks = doc.getLong("fileChunks");
                    db.upsertMaterial(id, doc.getString("courseId"), str(doc.getString("title")),
                            orDefault(doc.getString("type"), "notes"), str(doc.getString("body")),
                            Boolean.TRUE.equals(doc.getBoolean("approved")),
                            doc.getString("fileName"), doc.getString("fileMime"),
                            chunks == null ? 0 : chunks.intValue(), doc.getString("fileVersion"));
                }
                break;

            case "questions":
                if (removed) {
                    db.deleteByRemote("questions", id);
                } else if (doc.getString("courseId") != null) {
                    Long at = doc.getLong("createdAt");
                    db.upsertQuestion(id, str(doc.getString("userUid")), doc.getString("courseId"),
                            str(doc.getString("text")),
                            Boolean.TRUE.equals(doc.getBoolean("answered")),
                            at == null ? System.currentTimeMillis() : at);
                }
                break;

            case "bookmarks":
                if (removed) db.deleteByRemote("bookmarks", id);
                else if (doc.getString("materialId") != null) {
                    db.upsertBookmark(id, str(doc.getString("userUid")), doc.getString("materialId"));
                }
                break;

            case "notifications":
                if (!removed) {
                    Long at = doc.getLong("createdAt");
                    db.upsertNotification(id, str(doc.getString("userUid")),
                            str(doc.getString("title")), doc.getString("body"),
                            at == null ? System.currentTimeMillis() : at);
                }
                break;

            default:
                break;
        }
    }

    private void applyCourse(DocumentSnapshot doc) {
        db.upsertCourse(doc.getId(), doc.getString("code"), doc.getString("title"),
                doc.getString("faculty"), doc.getString("schedule"), doc.getString("joinCode"),
                doc.getString("teacherUid"));
    }

    private static String str(@Nullable String s) { return s == null ? "" : s; }
    private static String orDefault(@Nullable String s, String d) {
        return s == null || s.isEmpty() ? d : s;
    }
}
