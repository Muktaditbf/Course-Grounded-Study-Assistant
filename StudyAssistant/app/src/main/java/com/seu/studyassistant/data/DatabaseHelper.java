package com.seu.studyassistant.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.List;

/**
 * Local SQLite store. Implements SRS Appendix B (relational storage, role based access)
 * on device, with a chunk table standing in for the vector store described in SRS 5.1.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "study_assistant.db";
    private static final int DB_VERSION = 6;

    private static DatabaseHelper instance;

    /** Kept so onUpgrade can clear a session the rebuild is about to invalidate. */
    private final Context appContext;

    public static synchronized DatabaseHelper get(Context c) {
        if (instance == null) instance = new DatabaseHelper(c.getApplicationContext());
        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.appContext = context.getApplicationContext();
        // Write-ahead logging lets the screens keep reading while SyncManager writes a large
        // snapshot on its background thread, instead of blocking until the write finishes.
        setWriteAheadLoggingEnabled(true);
    }

    /**
     * Bumped on every change to materials or their passages. RetrievalEngine caches its
     * tokenised passages against this, so a question only re-reads the library after it changed.
     */
    private static volatile long materialsVersion = 0;

    public static long materialsVersion() { return materialsVersion; }
    private static void materialsChanged() { materialsVersion++; }

    /**
     * This database is a LOCAL MIRROR of Firestore, not the source of truth.
     *
     * Firestore (shared across devices) owns users, courses, enrolments, materials, questions,
     * bookmarks and notifications. SyncManager listens to it and upserts every change here, so
     * every screen keeps its fast synchronous reads. Each synced row carries the Firestore
     * document id in remote_id; user references are Firebase uids (TEXT), never local row ids.
     * Writes made through this class go to the mirror at once (so the UI updates instantly) and
     * are pushed to Firestore by CloudRepo; the snapshot that comes back is an idempotent upsert.
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        // Only the signed-in user is stored here. remote_id is the Firebase uid.
        db.execSQL("CREATE TABLE users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "remote_id TEXT NOT NULL UNIQUE," +
                "name TEXT NOT NULL, email TEXT NOT NULL, contact TEXT," +
                "role TEXT NOT NULL, tier TEXT NOT NULL DEFAULT 'free')");

        db.execSQL("CREATE TABLE courses (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "remote_id TEXT NOT NULL UNIQUE," +
                "code TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT ''," +
                "faculty TEXT, schedule TEXT, join_code TEXT NOT NULL DEFAULT ''," +
                "teacher_uid TEXT)");

        db.execSQL("CREATE TABLE enrollments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "remote_id TEXT NOT NULL UNIQUE," +
                "user_uid TEXT NOT NULL, course_id INTEGER NOT NULL)");

        // file_name / file_path / file_mime keep the ORIGINAL upload next to the extracted
        // text. They are device-local: only the uploading teacher's phone has the original file.
        // Everyone else reads the extracted text, which is what Firestore carries.
        db.execSQL("CREATE TABLE materials (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "remote_id TEXT NOT NULL UNIQUE," +
                "course_id INTEGER NOT NULL, title TEXT NOT NULL, type TEXT NOT NULL," +
                "body TEXT NOT NULL, approved INTEGER NOT NULL DEFAULT 0," +
                "file_name TEXT, file_path TEXT, file_mime TEXT," +
                "file_chunks INTEGER NOT NULL DEFAULT 0, file_version TEXT)");

        // Stands in for the vector store: one row per retrievable passage. Built locally from
        // the material body, so it is never uploaded.
        db.execSQL("CREATE TABLE chunks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "material_id INTEGER NOT NULL, course_id INTEGER NOT NULL, text TEXT NOT NULL)");

        db.execSQL("CREATE TABLE questions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "remote_id TEXT NOT NULL UNIQUE," +
                "user_uid TEXT NOT NULL, course_id INTEGER NOT NULL, text TEXT NOT NULL," +
                "answered INTEGER NOT NULL, created_at INTEGER NOT NULL)");

        // Points at the material's Firestore id, so a bookmark can arrive before its material.
        db.execSQL("CREATE TABLE bookmarks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "remote_id TEXT NOT NULL UNIQUE," +
                "user_uid TEXT NOT NULL, material_remote_id TEXT NOT NULL)");

        db.execSQL("CREATE TABLE notifications (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "remote_id TEXT NOT NULL UNIQUE," +
                "user_uid TEXT NOT NULL, title TEXT NOT NULL, body TEXT," +
                "created_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Everything here is re-downloaded from Firestore, so a rebuild loses nothing shared.
        for (String t : TABLES) db.execSQL("DROP TABLE IF EXISTS " + t);
        onCreate(db);

        // The rebuild empties users, so a session saved before the upgrade points at a row that
        // no longer exists. Clearing it turns that into one clean sign-in.
        SessionManager.clearSession(appContext);
    }

    private static final String[] TABLES = {"users", "courses", "enrollments", "materials",
            "chunks", "questions", "bookmarks", "notifications"};

    /**
     * Empties the mirror on sign-out so the next account never sees the previous one's data.
     * DELETE rather than DROP keeps AUTOINCREMENT counters, so ids never repeat.
     */
    public void clearAll() {
        cachedUserId = -1;
        cachedUid = null;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String t : TABLES) db.delete(t, null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        materialsChanged();
    }

    /** Runs a batch of mirror writes in one transaction. */
    public void inTransaction(Runnable work) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            work.run();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Splits material text into retrievable passages (SRS FR 2.2).
     *
     * Chunks break on sentence boundaries, never mid-sentence, so a retrieved passage always
     * reads as complete prose. Sentences are accumulated until the passage reaches roughly
     * TARGET_WORDS, and passages never overlap, so two neighbouring chunks cannot repeat
     * each other inside one answer.
     */
    void insertChunks(SQLiteDatabase db, long materialId, long courseId, String body) {
        final int TARGET_WORDS = 55;

        // Split after . ! or ? when followed by whitespace, and also on a paragraph break.
        //
        // The paragraph arm matters for slide decks: bullets are often fragments with no
        // terminal punctuation, so on sentence boundaries alone an entire deck would collapse
        // into a single chunk and retrieval would return the whole lecture as one passage.
        // A paragraph break is one slide, which is the right size for a passage.
        String[] sentences = body.split("(?<=[.!?])\\s+|\\n{2,}");

        StringBuilder current = new StringBuilder();
        int words = 0;

        // A single piece is only ever flushed once it is complete, so one oversized piece
        // becomes one oversized chunk. Real documents produce them - a flattened table or a
        // page of figures carries no . ! ? at all - and a 40,000 character "passage" makes
        // coverage scoring meaningless. Anything past this ceiling is cut on word boundaries.
        final int MAX_WORDS = TARGET_WORDS * 2;

        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;

            for (String piece : capLength(s, MAX_WORDS)) {
                if (current.length() > 0) current.append(' ');
                current.append(piece);
                words += piece.split("\\s+").length;

                if (words >= TARGET_WORDS) {
                    writeChunk(db, materialId, courseId, current.toString());
                    current.setLength(0);
                    words = 0;
                }
            }
        }
        if (current.length() > 0) writeChunk(db, materialId, courseId, current.toString());
    }

    /**
     * Returns the text as-is when it is within {@code maxWords}, or cut into word-boundary
     * pieces when it is not. Splitting mid-run only ever happens to text that carried no
     * sentence or paragraph boundary to split on in the first place.
     */
    private static List<String> capLength(String text, int maxWords) {
        String[] words = text.split("\\s+");
        if (words.length <= maxWords) return Collections.singletonList(text);

        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        int count = 0;
        for (String w : words) {
            if (piece.length() > 0) piece.append(' ');
            piece.append(w);
            if (++count >= maxWords) {
                pieces.add(piece.toString());
                piece.setLength(0);
                count = 0;
            }
        }
        if (piece.length() > 0) pieces.add(piece.toString());
        return pieces;
    }

    private void writeChunk(SQLiteDatabase db, long materialId, long courseId, String text) {
        ContentValues v = new ContentValues();
        v.put("material_id", materialId);
        v.put("course_id", courseId);
        v.put("text", text.trim());
        db.insert("chunks", null, v);
    }

    // ------------------------------------------------------------------ users

    /** Stores (or refreshes) the signed-in user's profile and returns the local row id. */
    public long upsertUser(String uid, String name, String email, String contact,
                           String role, String tier) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("remote_id", uid); v.put("name", name); v.put("email", email);
        v.put("contact", contact); v.put("role", role); v.put("tier", tier);
        long id = idByRemote(db, "users", uid);
        if (id > 0) {
            db.update("users", v, "id=?", new String[]{String.valueOf(id)});
            return id;
        }
        return db.insert("users", null, v);
    }

    public User userById(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,email,contact,role,tier FROM users WHERE id=?",
                new String[]{String.valueOf(id)});
        User u = c.moveToFirst() ? readUser(c) : null;
        c.close();
        return u;
    }

    /** Last answer of uidOf: every list query asks it, and it only changes at sign-in. */
    private volatile long cachedUserId = -1;
    private volatile String cachedUid;

    /** The Firebase uid behind a local user id, or null when the row is gone. */
    public String uidOf(long userId) {
        if (userId == cachedUserId && cachedUid != null) return cachedUid;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT remote_id FROM users WHERE id=?", new String[]{String.valueOf(userId)});
        String uid = c.moveToFirst() ? c.getString(0) : null;
        c.close();
        cachedUserId = userId;
        cachedUid = uid;
        return uid;
    }

    private User readUser(Cursor c) {
        return new User(c.getLong(0), c.getString(1), c.getString(2),
                c.getString(3), c.getString(4), c.getString(5));
    }

    /** Local mirror write plus Firestore. The payment itself is simulated (see PaymentActivity). */
    public void setTier(long userId, String tier) {
        String uid = uidOf(userId);
        if (uid == null) return;
        ContentValues v = new ContentValues();
        v.put("tier", tier);
        getWritableDatabase().update("users", v, "id=?", new String[]{String.valueOf(userId)});
        CloudRepo.updateTier(appContext, uid, tier);
    }

    // ---------------------------------------------------------------- courses

    /**
     * Course columns, with teacher_id resolved against the one stored user: it is that user's
     * local id when they own the course, and 0 for anyone else's. That keeps the ownership
     * checks in the activities (course.teacherId == user.id) working unchanged.
     */
    private static final String COURSE_COLS =
            "c.id,c.code,c.title,c.faculty,c.schedule,c.join_code," +
            "CASE WHEN c.teacher_uid IS NOT NULL AND " +
            "c.teacher_uid=(SELECT remote_id FROM users LIMIT 1) " +
            "THEN (SELECT id FROM users LIMIT 1) ELSE 0 END";

    public List<Course> coursesForStudent(long userId) {
        return courseQuery("SELECT " + COURSE_COLS + " FROM courses c " +
                "JOIN enrollments e ON e.course_id=c.id " +
                "WHERE e.user_uid=? AND c.code<>'' ORDER BY c.code",
                new String[]{String.valueOf(uidOf(userId))});
    }

    public List<Course> coursesForTeacher(long teacherId) {
        return courseQuery("SELECT " + COURSE_COLS + " FROM courses c " +
                "WHERE c.teacher_uid=? AND c.code<>'' ORDER BY c.code",
                new String[]{String.valueOf(uidOf(teacherId))});
    }

    private List<Course> courseQuery(String sql, String[] args) {
        List<Course> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        while (c.moveToNext()) {
            list.add(new Course(c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getString(4), c.getString(5), c.getLong(6)));
        }
        c.close();
        return list;
    }

    public Course courseById(long id) {
        List<Course> l = courseQuery("SELECT " + COURSE_COLS + " FROM courses c WHERE c.id=?",
                new String[]{String.valueOf(id)});
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * One definition of a join code, used by both create and join so they can never disagree.
     * Locale.ROOT matters: on a Turkish-locale device a default toUpperCase turns "i" into a
     * dotted capital, and the code would then never match.
     */
    public static String normaliseJoinCode(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** True when this student is enrolled in the course (SRS UC5 precondition). */
    public boolean isEnrolled(long userId, long courseId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM enrollments WHERE user_uid=? AND course_id=?",
                new String[]{String.valueOf(uidOf(userId)), String.valueOf(courseId)});
        boolean yes = c.moveToFirst();
        c.close();
        return yes;
    }

    // -------------------------------------------------------------- materials

    public List<Material> materials(long courseId, boolean approvedOnly) {
        List<Material> list = new ArrayList<>();
        String sql = "SELECT id,course_id,title,type,body,approved,file_name,file_path,file_mime,file_chunks,file_version "
                + "FROM materials WHERE course_id=?"
                + (approvedOnly ? " AND approved=1" : "") + " ORDER BY approved DESC, id";
        Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(courseId)});
        while (c.moveToNext()) list.add(readMaterial(c));
        c.close();
        return list;
    }

    public Material materialById(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course_id,title,type,body,approved,file_name,file_path,file_mime,file_chunks,file_version "
                        + "FROM materials WHERE id=?",
                new String[]{String.valueOf(id)});
        Material m = c.moveToFirst() ? readMaterial(c) : null;
        c.close();
        return m;
    }

    private Material readMaterial(Cursor c) {
        Material m = new Material(c.getLong(0), c.getLong(1), c.getString(2),
                c.getString(3), c.getString(4), c.getInt(5) == 1);
        if (c.getColumnCount() > 6) {
            m.fileName = c.getString(6);
            m.filePath = c.getString(7);
            m.fileMime = c.getString(8);
        }
        if (c.getColumnCount() > 9) {
            m.fileChunks = c.getInt(9);
            m.fileVersion = c.getString(10);
        }
        return m;
    }

    public long addMaterial(long courseId, String title, String type, String body, boolean approved) {
        return addMaterial(courseId, title, type, body, approved, null, null, null);
    }

    /**
     * Teacher upload. The row is written locally first (carrying the device-local original
     * file), then the text is pushed to Firestore under the same id, so the snapshot that comes
     * back updates this row instead of creating a second one.
     *
     * @param fileName original upload name, or null when the teacher pasted the text
     * @param filePath where the original was copied inside app storage
     * @param fileMime the upload's MIME type, used to decide how a student opens it
     */
    public long addMaterial(long courseId, String title, String type, String body,
                            boolean approved, String fileName, String filePath, String fileMime) {
        SQLiteDatabase db = getWritableDatabase();
        String remoteId = CloudRepo.newId("materials");
        CloudRepo.FileMeta file = CloudRepo.FileMeta.of(filePath, fileName, fileMime);

        ContentValues v = new ContentValues();
        v.put("remote_id", remoteId);
        v.put("course_id", courseId); v.put("title", title); v.put("type", type);
        v.put("body", body); v.put("approved", approved ? 1 : 0);
        v.put("file_name", fileName); v.put("file_path", filePath); v.put("file_mime", fileMime);
        v.put("file_chunks", file == null ? 0 : file.chunks);
        v.put("file_version", file == null ? null : file.version);
        long id = db.insert("materials", null, v);
        insertChunks(db, id, courseId, body);
        materialsChanged();

        String[] course = courseRemote(courseId);
        if (course != null) {
            CloudRepo.pushMaterial(appContext, remoteId, course[0], course[1],
                    title, type, body, approved, file, filePath);
        }
        return id;
    }

    /** SRS FR 3.1 / UC4: only an approved material may be retrieved from. */
    public void setApproved(long materialId, boolean approved) {
        ContentValues v = new ContentValues();
        v.put("approved", approved ? 1 : 0);
        getWritableDatabase().update("materials", v, "id=?",
                new String[]{String.valueOf(materialId)});
        materialsChanged();
        String remoteId = remoteOf("materials", materialId);
        if (remoteId != null) CloudRepo.setApproved(appContext, remoteId, approved);
    }

    /** UC6: keyword search across the materials of the courses a student is enrolled in. */
    public List<Material> searchMaterials(long userId, String keyword) {
        List<Material> list = new ArrayList<>();
        // The keyword is matched literally: % and _ typed by the student are escaped, so they
        // do not act as SQL wildcards (a search for "50%" must not match everything).
        String escaped = keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String like = "%" + escaped + "%";
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT m.id,m.course_id,m.title,m.type,m.body,m.approved FROM materials m " +
                "JOIN enrollments e ON e.course_id=m.course_id " +
                "WHERE e.user_uid=? AND m.approved=1 AND (m.title LIKE ? ESCAPE '\\' OR m.body LIKE ? ESCAPE '\\') " +
                "ORDER BY m.id",
                new String[]{String.valueOf(uidOf(userId)), like, like});
        while (c.moveToNext()) list.add(readMaterial(c));
        c.close();
        return list;
    }

    // -------------------------------------------------------------- questions

    public void logQuestion(long userId, long courseId, String text, boolean answered) {
        String uid = uidOf(userId);
        if (uid == null) return;
        String[] course = courseRemote(courseId);
        if (course == null) {
            // A chat question belongs to no course, so it is not shared; it is still counted
            // here so the free tier's daily cap covers the chat as well.
            ContentValues v = new ContentValues();
            v.put("remote_id", "local-" + java.util.UUID.randomUUID());
            v.put("user_uid", uid); v.put("course_id", -1); v.put("text", text);
            v.put("answered", answered ? 1 : 0); v.put("created_at", System.currentTimeMillis());
            getWritableDatabase().insert("questions", null, v);
            return;
        }

        String remoteId = CloudRepo.newId("questions");
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("remote_id", remoteId);
        v.put("user_uid", uid); v.put("course_id", courseId); v.put("text", text);
        v.put("answered", answered ? 1 : 0); v.put("created_at", now);
        getWritableDatabase().insert("questions", null, v);
        CloudRepo.pushQuestion(appContext, remoteId, uid, course[0], course[1], text, answered, now);
    }

    public int countQuestions(long userId, Boolean answered) {
        String sql = "SELECT COUNT(*) FROM questions WHERE user_uid=?";
        if (answered != null) sql += " AND answered=" + (answered ? 1 : 0);
        Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(uidOf(userId))});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** Free tier daily cap (Cost Report Section 4). Premium removes it. */
    public int questionsToday(long userId) {
        long dayStart = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM questions WHERE user_uid=? AND created_at>?",
                new String[]{String.valueOf(uidOf(userId)), String.valueOf(dayStart)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int countQuestionsForCourse(long courseId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM questions WHERE course_id=?",
                new String[]{String.valueOf(courseId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    // -------------------------------------------------------------- bookmarks

    public boolean isBookmarked(long userId, long materialId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM bookmarks b JOIN materials m ON m.remote_id=b.material_remote_id " +
                "WHERE b.user_uid=? AND m.id=?",
                new String[]{String.valueOf(uidOf(userId)), String.valueOf(materialId)});
        boolean b = c.moveToFirst();
        c.close();
        return b;
    }

    public boolean toggleBookmark(long userId, long materialId) {
        String uid = uidOf(userId);
        String materialRemote = remoteOf("materials", materialId);
        if (uid == null || materialRemote == null) return false;

        String docId = uid + "_" + materialRemote;
        if (isBookmarked(userId, materialId)) {
            getWritableDatabase().delete("bookmarks", "remote_id=?", new String[]{docId});
            CloudRepo.deleteBookmark(appContext, docId);
            return false;
        }
        ContentValues v = new ContentValues();
        v.put("remote_id", docId); v.put("user_uid", uid); v.put("material_remote_id", materialRemote);
        getWritableDatabase().insert("bookmarks", null, v);
        CloudRepo.pushBookmark(appContext, docId, uid, materialRemote);
        return true;
    }

    /**
     * Saved materials, filtered by the Content Lock.
     *
     * The approved = 1 clause matters: without it a student who bookmarked a material while it
     * was approved would keep reading its full text after the teacher revoked it. The bookmark
     * row itself is kept, so the item reappears if the teacher approves the material again.
     */
    public List<Material> bookmarks(long userId) {
        List<Material> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT m.id,m.course_id,m.title,m.type,m.body,m.approved FROM materials m " +
                "JOIN bookmarks b ON b.material_remote_id=m.remote_id " +
                "WHERE b.user_uid=? AND m.approved=1 ORDER BY b.id DESC",
                new String[]{String.valueOf(uidOf(userId))});
        while (c.moveToNext()) list.add(readMaterial(c));
        c.close();
        return list;
    }

    /** Counts approved material without loading any body text. */
    public int countApprovedMaterials(long courseId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM materials WHERE course_id=? AND approved=1",
                new String[]{String.valueOf(courseId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** {approved, pending} for one course, again without loading bodies. */
    public int[] materialCounts(long courseId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(approved=1), SUM(approved=0) FROM materials WHERE course_id=?",
                new String[]{String.valueOf(courseId)});
        int[] counts = {0, 0};
        if (c.moveToFirst()) {
            counts[0] = c.getInt(0);
            counts[1] = c.getInt(1);
        }
        c.close();
        return counts;
    }

    public int countBookmarks(long userId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM bookmarks WHERE user_uid=?",
                new String[]{String.valueOf(uidOf(userId))});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    // ---------------------------------------------------------- notifications

    /** A notification for the signed-in user themselves. */
    public void notify(long userId, String title, String body) {
        String uid = uidOf(userId);
        if (uid == null) return;
        String remoteId = CloudRepo.newId("notifications");
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("remote_id", remoteId);
        v.put("user_uid", uid); v.put("title", title); v.put("body", body);
        v.put("created_at", now);
        getWritableDatabase().insert("notifications", null, v);
        CloudRepo.pushNotification(appContext, remoteId, uid, null, title, body, now);
    }

    /**
     * Notifies every student enrolled in the course (SRS FR 9.1). Each student's notification
     * is a Firestore document; their own device picks it up through its listener.
     */
    public void notifyCourseStudents(long courseId, String title, String body) {
        String[] course = courseRemote(courseId);
        if (course == null) return;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT user_uid FROM enrollments WHERE course_id=?",
                new String[]{String.valueOf(courseId)});
        long now = System.currentTimeMillis();
        while (c.moveToNext()) {
            CloudRepo.pushNotification(appContext, CloudRepo.newId("notifications"),
                    c.getString(0), course[0], title, body, now);
        }
        c.close();
    }

    public List<String[]> notifications(long userId) {
        List<String[]> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT title,body FROM notifications WHERE user_uid=? ORDER BY id DESC",
                new String[]{String.valueOf(uidOf(userId))});
        while (c.moveToNext()) list.add(new String[]{c.getString(0), c.getString(1)});
        c.close();
        return list;
    }

    /**
     * How many notifications arrived after the id the student last saw.
     *
     * A watermark in SessionManager is used instead of a read/unread column so the badge
     * costs no schema migration - the notifications table is append-only and ids only grow.
     */
    public int countNotificationsAfter(long userId, long lastSeenId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM notifications WHERE user_uid=? AND id>?",
                new String[]{String.valueOf(uidOf(userId)), String.valueOf(lastSeenId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** Id of the student's newest notification, or 0 when they have none. */
    public long newestNotificationId(long userId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(id) FROM notifications WHERE user_uid=?",
                new String[]{String.valueOf(uidOf(userId))});
        long id = c.moveToFirst() ? c.getLong(0) : 0;
        c.close();
        return id;
    }

    public int countStudents(long courseId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM enrollments WHERE course_id=?",
                new String[]{String.valueOf(courseId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    // ------------------------------------------------------- teacher edit and delete

    /** Edits a course's details. The join code is fixed once created: students hold it. */
    public void updateCourse(long courseId, String code, String title, String faculty,
                             String schedule) {
        ContentValues v = new ContentValues();
        v.put("code", code); v.put("title", title);
        v.put("faculty", faculty); v.put("schedule", schedule);
        getWritableDatabase().update("courses", v, "id=?", new String[]{String.valueOf(courseId)});
        String remoteId = remoteOf("courses", courseId);
        if (remoteId != null) {
            CloudRepo.updateCourse(appContext, remoteId, code, title, faculty, schedule);
        }
    }

    /**
     * Deletes a course everywhere: its materials, enrolments, questions and join code in
     * Firestore, and the local mirror at once. Students' devices drop it as the deletions sync.
     */
    public void deleteCourse(long courseId, Callback<Void> cb) {
        Course c = courseById(courseId);
        String[] remote = courseRemote(courseId);
        String uid = remote == null ? null : remote[1];
        if (c == null || remote == null || uid == null) { cb.onSuccess(null); return; }

        final String remoteId = remote[0];
        CloudRepo.deleteCourse(appContext, uid, remoteId, c.joinCode, new Callback<Void>() {
            @Override public void onSuccess(Void v) {
                deleteCourseLocal(remoteId);
                cb.onSuccess(null);
            }
            @Override public void onError(Exception e) { cb.onError(e); }
        });
    }

    /** Removes a course and everything under it from the local mirror, files included. */
    public void deleteCourseLocal(String courseRemoteId) {
        SQLiteDatabase db = getWritableDatabase();
        long courseId = idByRemote(db, "courses", courseRemoteId);
        if (courseId <= 0) return;
        String cid = String.valueOf(courseId);

        Cursor c = db.rawQuery("SELECT file_path FROM materials WHERE course_id=?", new String[]{cid});
        while (c.moveToNext()) FileStore.delete(c.getString(0));
        c.close();

        db.delete("chunks", "course_id=?", new String[]{cid});
        db.delete("materials", "course_id=?", new String[]{cid});
        db.delete("questions", "course_id=?", new String[]{cid});
        db.delete("enrollments", "course_id=?", new String[]{cid});
        db.delete("courses", "id=?", new String[]{cid});
        materialsChanged();
    }

    /**
     * Edits a material's text. Retrieval re-indexes it, and the approval state is untouched.
     *
     * @param replaceFile true when the teacher picked a new file, which replaces the old one
     */
    public void updateMaterial(long materialId, String title, String type, String body,
                               boolean replaceFile, String fileName, String filePath,
                               String fileMime) {
        SQLiteDatabase db = getWritableDatabase();
        Material old = materialById(materialId);
        if (old == null) return;

        CloudRepo.FileMeta file = replaceFile ? CloudRepo.FileMeta.of(filePath, fileName, fileMime) : null;
        ContentValues v = new ContentValues();
        v.put("title", title); v.put("type", type); v.put("body", body);
        if (replaceFile) {
            v.put("file_name", fileName); v.put("file_path", filePath); v.put("file_mime", fileMime);
            v.put("file_chunks", file == null ? 0 : file.chunks);
            v.put("file_version", file == null ? null : file.version);
            if (old.filePath != null && !old.filePath.equals(filePath)) FileStore.delete(old.filePath);
        }
        db.update("materials", v, "id=?", new String[]{String.valueOf(materialId)});

        if (!body.equals(old.body)) {
            db.delete("chunks", "material_id=?", new String[]{String.valueOf(materialId)});
            insertChunks(db, materialId, old.courseId, body);
        }
        materialsChanged();
        String remoteId = remoteOf("materials", materialId);
        if (remoteId != null) {
            CloudRepo.updateMaterial(appContext, remoteId, title, type, body,
                    replaceFile, file, filePath, old.fileChunks);
        }
    }

    /** Deletes a material, its search index, its stored file, and the Firestore document. */
    public void deleteMaterial(long materialId) {
        Material m = materialById(materialId);
        if (m == null) return;
        String remoteId = remoteOf("materials", materialId);

        SQLiteDatabase db = getWritableDatabase();
        db.delete("chunks", "material_id=?", new String[]{String.valueOf(materialId)});
        db.delete("materials", "id=?", new String[]{String.valueOf(materialId)});
        materialsChanged();
        FileStore.delete(m.filePath);
        if (remoteId != null) CloudRepo.deleteMaterial(appContext, remoteId, m.fileChunks);
    }

    // ------------------------------------------------- mirror writes (SyncManager)
    //
    // Every method below is an idempotent upsert keyed on the Firestore document id, so a
    // snapshot that echoes one of our own optimistic writes changes nothing.

    private long idByRemote(SQLiteDatabase db, String table, String remoteId) {
        Cursor c = db.rawQuery("SELECT id FROM " + table + " WHERE remote_id=?",
                new String[]{remoteId});
        long id = c.moveToFirst() ? c.getLong(0) : -1;
        c.close();
        return id;
    }

    private String remoteOf(String table, long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT remote_id FROM " + table + " WHERE id=?", new String[]{String.valueOf(id)});
        String r = c.moveToFirst() ? c.getString(0) : null;
        c.close();
        return r;
    }

    /** {course remote id, teacher uid} for a local course id, or null. */
    private String[] courseRemote(long courseId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT remote_id,teacher_uid FROM courses WHERE id=?",
                new String[]{String.valueOf(courseId)});
        String[] r = c.moveToFirst() ? new String[]{c.getString(0), c.getString(1)} : null;
        c.close();
        return r;
    }

    /**
     * Returns the local id for a course, inserting an empty placeholder when its own document
     * has not arrived yet. Enrolments, materials and questions can be delivered before the
     * course; the placeholder (code = '') stays hidden from every list until it is filled in.
     */
    public long ensureCourse(String remoteId) {
        SQLiteDatabase db = getWritableDatabase();
        long id = idByRemote(db, "courses", remoteId);
        if (id > 0) return id;
        ContentValues v = new ContentValues();
        v.put("remote_id", remoteId);
        return db.insert("courses", null, v);
    }

    public void upsertCourse(String remoteId, String code, String title, String faculty,
                             String schedule, String joinCode, String teacherUid) {
        long id = ensureCourse(remoteId);
        ContentValues v = new ContentValues();
        v.put("code", code == null ? "" : code);
        v.put("title", title == null ? "" : title);
        v.put("faculty", faculty); v.put("schedule", schedule);
        v.put("join_code", joinCode == null ? "" : joinCode);
        v.put("teacher_uid", teacherUid);
        getWritableDatabase().update("courses", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void upsertEnrollment(String remoteId, String userUid, String courseRemoteId) {
        SQLiteDatabase db = getWritableDatabase();
        long courseId = ensureCourse(courseRemoteId);
        ContentValues v = new ContentValues();
        v.put("remote_id", remoteId); v.put("user_uid", userUid); v.put("course_id", courseId);
        long id = idByRemote(db, "enrollments", remoteId);
        if (id > 0) db.update("enrollments", v, "id=?", new String[]{String.valueOf(id)});
        else db.insert("enrollments", null, v);
    }

    /** Keeps the device-local file columns; re-chunks only when the text actually changed. */
    public void upsertMaterial(String remoteId, String courseRemoteId, String title, String type,
                               String body, boolean approved, String fileName, String fileMime,
                               int fileChunks, String fileVersion) {
        SQLiteDatabase db = getWritableDatabase();
        long courseId = ensureCourse(courseRemoteId);
        long id = idByRemote(db, "materials", remoteId);

        ContentValues v = new ContentValues();
        v.put("course_id", courseId); v.put("title", title); v.put("type", type);
        v.put("body", body); v.put("approved", approved ? 1 : 0);
        v.put("file_name", fileName); v.put("file_mime", fileMime);
        v.put("file_chunks", fileChunks); v.put("file_version", fileVersion);

        if (id <= 0) {
            v.put("remote_id", remoteId);
            id = db.insert("materials", null, v);
            insertChunks(db, id, courseId, body);
            materialsChanged();
            return;
        }

        String oldBody = null, oldVersion = null, oldPath = null;
        Cursor c = db.rawQuery("SELECT body,file_version,file_path FROM materials WHERE id=?",
                new String[]{String.valueOf(id)});
        if (c.moveToFirst()) { oldBody = c.getString(0); oldVersion = c.getString(1); oldPath = c.getString(2); }
        c.close();

        // The teacher replaced the file: the copy on this device is out of date.
        boolean sameVersion = fileVersion == null ? oldVersion == null : fileVersion.equals(oldVersion);
        if (!sameVersion && oldPath != null) {
            FileStore.delete(oldPath);
            v.putNull("file_path");
        }

        db.update("materials", v, "id=?", new String[]{String.valueOf(id)});
        if (!body.equals(oldBody)) {
            db.delete("chunks", "material_id=?", new String[]{String.valueOf(id)});
            insertChunks(db, id, courseId, body);
        }
        materialsChanged();
    }

    /** Records where this device stored its downloaded copy of a material's original file. */
    public void setLocalFilePath(long materialId, String path) {
        ContentValues v = new ContentValues();
        v.put("file_path", path);
        getWritableDatabase().update("materials", v, "id=?", new String[]{String.valueOf(materialId)});
    }

    /** The Firestore id of a course, for an external retrieval service; null if unknown. */
    public String courseRemoteId(long courseId) {
        String[] c = courseRemote(courseId);
        return c == null ? null : c[0];
    }

    /** The Firestore id of a material, for downloading its file. */
    public String materialRemoteId(long materialId) { return remoteOf("materials", materialId); }

    public void upsertQuestion(String remoteId, String userUid, String courseRemoteId,
                               String text, boolean answered, long createdAt) {
        SQLiteDatabase db = getWritableDatabase();
        long courseId = ensureCourse(courseRemoteId);
        ContentValues v = new ContentValues();
        v.put("remote_id", remoteId); v.put("user_uid", userUid); v.put("course_id", courseId);
        v.put("text", text); v.put("answered", answered ? 1 : 0); v.put("created_at", createdAt);
        long id = idByRemote(db, "questions", remoteId);
        if (id > 0) db.update("questions", v, "id=?", new String[]{String.valueOf(id)});
        else db.insert("questions", null, v);
    }

    public void upsertBookmark(String remoteId, String userUid, String materialRemoteId) {
        SQLiteDatabase db = getWritableDatabase();
        if (idByRemote(db, "bookmarks", remoteId) > 0) return;   // keeps the original order
        ContentValues v = new ContentValues();
        v.put("remote_id", remoteId); v.put("user_uid", userUid);
        v.put("material_remote_id", materialRemoteId);
        db.insert("bookmarks", null, v);
    }

    public void upsertNotification(String remoteId, String userUid, String title, String body,
                                   long createdAt) {
        SQLiteDatabase db = getWritableDatabase();
        if (idByRemote(db, "notifications", remoteId) > 0) return;   // append-only
        ContentValues v = new ContentValues();
        v.put("remote_id", remoteId); v.put("user_uid", userUid);
        v.put("title", title == null ? "" : title); v.put("body", body);
        v.put("created_at", createdAt);
        db.insert("notifications", null, v);
    }

    /** Applies a user-profile change (for example a tier upgrade seen from another device). */
    public void updateUserProfile(String uid, String name, String role, String tier) {
        ContentValues v = new ContentValues();
        if (name != null) v.put("name", name);
        if (role != null) v.put("role", role);
        if (tier != null) v.put("tier", tier);
        if (v.size() == 0) return;
        getWritableDatabase().update("users", v, "remote_id=?", new String[]{uid});
    }

    /**
     * Removes a mirrored row after Firestore reports the document gone, or no longer visible to
     * this user - which is how a teacher revoking approval removes a material from a student.
     */
    public void deleteByRemote(String table, String remoteId) {
        SQLiteDatabase db = getWritableDatabase();
        if ("materials".equals(table)) {
            long id = idByRemote(db, "materials", remoteId);
            if (id > 0) {
                db.delete("chunks", "material_id=?", new String[]{String.valueOf(id)});
                Cursor c = db.rawQuery("SELECT file_path FROM materials WHERE id=?",
                        new String[]{String.valueOf(id)});
                if (c.moveToFirst()) FileStore.delete(c.getString(0));
                c.close();
            }
            materialsChanged();
        }
        db.delete(table, "remote_id=?", new String[]{remoteId});
    }
}
