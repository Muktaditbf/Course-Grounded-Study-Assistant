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
    private static final int DB_VERSION = 4;

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
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL, email TEXT NOT NULL UNIQUE, contact TEXT," +
                "password TEXT NOT NULL, role TEXT NOT NULL, tier TEXT NOT NULL DEFAULT 'free')");

        db.execSQL("CREATE TABLE courses (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "code TEXT NOT NULL, title TEXT NOT NULL, faculty TEXT," +
                "schedule TEXT, join_code TEXT NOT NULL UNIQUE, teacher_id INTEGER)");

        db.execSQL("CREATE TABLE enrollments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "user_id INTEGER NOT NULL, course_id INTEGER NOT NULL)");

        // file_name / file_path / file_mime keep the ORIGINAL upload next to the extracted
        // text. The text is what retrieval indexes; the file is what a student actually reads,
        // so a lecture PDF opens as its real pages rather than a wall of stripped characters.
        db.execSQL("CREATE TABLE materials (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "course_id INTEGER NOT NULL, title TEXT NOT NULL, type TEXT NOT NULL," +
                "body TEXT NOT NULL, approved INTEGER NOT NULL DEFAULT 0," +
                "file_name TEXT, file_path TEXT, file_mime TEXT)");

        // Stands in for the vector store: one row per retrievable passage.
        db.execSQL("CREATE TABLE chunks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "material_id INTEGER NOT NULL, course_id INTEGER NOT NULL, text TEXT NOT NULL)");

        db.execSQL("CREATE TABLE questions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "user_id INTEGER NOT NULL, course_id INTEGER NOT NULL, text TEXT NOT NULL," +
                "answered INTEGER NOT NULL, created_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE bookmarks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "user_id INTEGER NOT NULL, material_id INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE notifications (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "user_id INTEGER NOT NULL, title TEXT NOT NULL, body TEXT," +
                "created_at INTEGER NOT NULL)");

        seed(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        for (String t : new String[]{"users", "courses", "enrollments", "materials", "chunks",
                "questions", "bookmarks", "notifications"}) {
            db.execSQL("DROP TABLE IF EXISTS " + t);
        }
        onCreate(db);

        // The rebuild re-seeds users with fresh ids, so a session saved before the upgrade now
        // points at a row that no longer exists. currentUser() would return null and every
        // screen would bounce to the login page on its own - the "it logs me out by itself"
        // bug. Clearing the session here turns that into one clean sign-in instead.
        SessionManager.clearSession(appContext);
    }

    // ---------------------------------------------------------------- seeding

    private void seed(SQLiteDatabase db) {
        long teacherId = 0, studentId = 0;
        for (String[] a : SeedData.ACCOUNTS) {
            ContentValues v = new ContentValues();
            v.put("name", a[0]); v.put("email", a[1]); v.put("password", a[2]);
            v.put("role", a[3]); v.put("contact", "01700000000"); v.put("tier", "free");
            long id = db.insert("users", null, v);
            if ("teacher".equals(a[3])) teacherId = id; else studentId = id;
        }

        for (String[] c : SeedData.COURSES) {
            ContentValues v = new ContentValues();
            v.put("code", c[0]); v.put("title", c[1]); v.put("faculty", c[2]);
            v.put("schedule", c[3]); v.put("join_code", c[4]); v.put("teacher_id", teacherId);
            long courseId = db.insert("courses", null, v);

            // The student is pre-enrolled in their registered courses.
            ContentValues e = new ContentValues();
            e.put("user_id", studentId); e.put("course_id", courseId);
            db.insert("enrollments", null, e);
        }

        for (String[] m : SeedData.MATERIALS) {
            long courseId = courseIdByCode(db, m[0]);
            if (courseId <= 0) continue;
            ContentValues v = new ContentValues();
            v.put("course_id", courseId); v.put("title", m[1]); v.put("type", m[2]);
            v.put("approved", Integer.parseInt(m[3])); v.put("body", m[4]);
            long materialId = db.insert("materials", null, v);
            insertChunks(db, materialId, courseId, m[4]);
        }

        ContentValues n = new ContentValues();
        n.put("user_id", studentId);
        n.put("title", "Welcome to Study Assistant");
        n.put("body", "Your teacher has approved course materials. Open a course and ask a question.");
        n.put("created_at", System.currentTimeMillis());
        db.insert("notifications", null, n);
    }

    private long courseIdByCode(SQLiteDatabase db, String code) {
        Cursor c = db.rawQuery("SELECT id FROM courses WHERE code=?", new String[]{code});
        long id = c.moveToFirst() ? c.getLong(0) : -1;
        c.close();
        return id;
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

    public User login(String email, String password) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,email,contact,role,tier FROM users WHERE email=? AND password=?",
                new String[]{email.trim(), password});
        User u = c.moveToFirst() ? readUser(c) : null;
        c.close();
        return u;
    }

    public boolean emailExists(String email) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM users WHERE email=?",
                new String[]{email.trim()});
        boolean e = c.moveToFirst();
        c.close();
        return e;
    }

    public long signUp(String name, String email, String contact, String password, String role) {
        ContentValues v = new ContentValues();
        v.put("name", name); v.put("email", email.trim()); v.put("contact", contact);
        v.put("password", password); v.put("role", role); v.put("tier", "free");
        return getWritableDatabase().insert("users", null, v);
    }

    public User userById(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,email,contact,role,tier FROM users WHERE id=?",
                new String[]{String.valueOf(id)});
        User u = c.moveToFirst() ? readUser(c) : null;
        c.close();
        return u;
    }

    private User readUser(Cursor c) {
        return new User(c.getLong(0), c.getString(1), c.getString(2),
                c.getString(3), c.getString(4), c.getString(5));
    }

    public void setTier(long userId, String tier) {
        ContentValues v = new ContentValues();
        v.put("tier", tier);
        getWritableDatabase().update("users", v, "id=?", new String[]{String.valueOf(userId)});
    }

    // ---------------------------------------------------------------- courses

    public List<Course> coursesForStudent(long userId) {
        return courseQuery("SELECT c.id,c.code,c.title,c.faculty,c.schedule,c.join_code,c.teacher_id " +
                "FROM courses c JOIN enrollments e ON e.course_id=c.id WHERE e.user_id=? ORDER BY c.code",
                new String[]{String.valueOf(userId)});
    }

    public List<Course> coursesForTeacher(long teacherId) {
        return courseQuery("SELECT id,code,title,faculty,schedule,join_code,teacher_id " +
                "FROM courses WHERE teacher_id=? ORDER BY code",
                new String[]{String.valueOf(teacherId)});
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
        List<Course> l = courseQuery("SELECT id,code,title,faculty,schedule,join_code,teacher_id " +
                "FROM courses WHERE id=?", new String[]{String.valueOf(id)});
        return l.isEmpty() ? null : l.get(0);
    }

    /** Returns the joined course, or null when the code matches nothing. */
    /**
     * One definition of a join code, used by both create and join so they can never disagree.
     * Locale.ROOT matters: on a Turkish-locale device a default toUpperCase turns "i" into a
     * dotted capital, and the code would then never match.
     */
    public static String normaliseJoinCode(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\s+", "").toUpperCase(Locale.ROOT);
    }

    /** True when a course already uses this join code (the column is UNIQUE). */
    public boolean joinCodeExists(String joinCode) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM courses WHERE join_code=?",
                new String[]{normaliseJoinCode(joinCode)});
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    public Course joinByCode(long userId, String joinCode) {
        List<Course> l = courseQuery("SELECT id,code,title,faculty,schedule,join_code,teacher_id " +
                "FROM courses WHERE join_code=?", new String[]{normaliseJoinCode(joinCode)});
        if (l.isEmpty()) return null;
        Course course = l.get(0);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM enrollments WHERE user_id=? AND course_id=?",
                new String[]{String.valueOf(userId), String.valueOf(course.id)});
        boolean already = c.moveToFirst();
        c.close();
        if (!already) {
            ContentValues v = new ContentValues();
            v.put("user_id", userId); v.put("course_id", course.id);
            getWritableDatabase().insert("enrollments", null, v);
        }
        return course;
    }

    /** True when this student is enrolled in the course (SRS UC5 precondition). */
    public boolean isEnrolled(long userId, long courseId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM enrollments WHERE user_id=? AND course_id=?",
                new String[]{String.valueOf(userId), String.valueOf(courseId)});
        boolean yes = c.moveToFirst();
        c.close();
        return yes;
    }

    public long createCourse(String code, String title, String faculty, String schedule,
                             String joinCode, long teacherId) {
        ContentValues v = new ContentValues();
        v.put("code", code); v.put("title", title); v.put("faculty", faculty);
        v.put("schedule", schedule); v.put("join_code", normaliseJoinCode(joinCode));
        v.put("teacher_id", teacherId);
        return getWritableDatabase().insert("courses", null, v);
    }

    // -------------------------------------------------------------- materials

    public List<Material> materials(long courseId, boolean approvedOnly) {
        List<Material> list = new ArrayList<>();
        String sql = "SELECT id,course_id,title,type,body,approved,file_name,file_path,file_mime "
                + "FROM materials WHERE course_id=?"
                + (approvedOnly ? " AND approved=1" : "") + " ORDER BY approved DESC, id";
        Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(courseId)});
        while (c.moveToNext()) list.add(readMaterial(c));
        c.close();
        return list;
    }

    public Material materialById(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course_id,title,type,body,approved,file_name,file_path,file_mime "
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
        return m;
    }

    public long addMaterial(long courseId, String title, String type, String body, boolean approved) {
        return addMaterial(courseId, title, type, body, approved, null, null, null);
    }

    /**
     * @param fileName original upload name, or null when the teacher pasted the text
     * @param filePath where the original was copied inside app storage
     * @param fileMime the upload's MIME type, used to decide how a student opens it
     */
    public long addMaterial(long courseId, String title, String type, String body,
                            boolean approved, String fileName, String filePath, String fileMime) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("course_id", courseId); v.put("title", title); v.put("type", type);
        v.put("body", body); v.put("approved", approved ? 1 : 0);
        v.put("file_name", fileName); v.put("file_path", filePath); v.put("file_mime", fileMime);
        long id = db.insert("materials", null, v);
        insertChunks(db, id, courseId, body);
        return id;
    }

    /** SRS FR 3.1 / UC4: only an approved material may be retrieved from. */
    public void setApproved(long materialId, boolean approved) {
        ContentValues v = new ContentValues();
        v.put("approved", approved ? 1 : 0);
        getWritableDatabase().update("materials", v, "id=?",
                new String[]{String.valueOf(materialId)});
    }

    /** UC6: keyword search across the materials of the courses a student is enrolled in. */
    public List<Material> searchMaterials(long userId, String keyword) {
        List<Material> list = new ArrayList<>();
        String like = "%" + keyword.trim() + "%";
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT m.id,m.course_id,m.title,m.type,m.body,m.approved FROM materials m " +
                "JOIN enrollments e ON e.course_id=m.course_id " +
                "WHERE e.user_id=? AND m.approved=1 AND (m.title LIKE ? OR m.body LIKE ?) " +
                "ORDER BY m.id",
                new String[]{String.valueOf(userId), like, like});
        while (c.moveToNext()) list.add(readMaterial(c));
        c.close();
        return list;
    }

    // -------------------------------------------------------------- questions

    public void logQuestion(long userId, long courseId, String text, boolean answered) {
        ContentValues v = new ContentValues();
        v.put("user_id", userId); v.put("course_id", courseId); v.put("text", text);
        v.put("answered", answered ? 1 : 0); v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("questions", null, v);
    }

    public int countQuestions(long userId, Boolean answered) {
        String sql = "SELECT COUNT(*) FROM questions WHERE user_id=?";
        if (answered != null) sql += " AND answered=" + (answered ? 1 : 0);
        Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(userId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** Free tier daily cap (Cost Report Section 4). Premium removes it. */
    public int questionsToday(long userId) {
        long dayStart = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM questions WHERE user_id=? AND created_at>?",
                new String[]{String.valueOf(userId), String.valueOf(dayStart)});
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
                "SELECT 1 FROM bookmarks WHERE user_id=? AND material_id=?",
                new String[]{String.valueOf(userId), String.valueOf(materialId)});
        boolean b = c.moveToFirst();
        c.close();
        return b;
    }

    public boolean toggleBookmark(long userId, long materialId) {
        if (isBookmarked(userId, materialId)) {
            getWritableDatabase().delete("bookmarks", "user_id=? AND material_id=?",
                    new String[]{String.valueOf(userId), String.valueOf(materialId)});
            return false;
        }
        ContentValues v = new ContentValues();
        v.put("user_id", userId); v.put("material_id", materialId);
        getWritableDatabase().insert("bookmarks", null, v);
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
                "JOIN bookmarks b ON b.material_id=m.id " +
                "WHERE b.user_id=? AND m.approved=1 ORDER BY b.id DESC",
                new String[]{String.valueOf(userId)});
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
                "SELECT COUNT(*) FROM bookmarks WHERE user_id=?",
                new String[]{String.valueOf(userId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    // ---------------------------------------------------------- notifications

    public void notify(long userId, String title, String body) {
        ContentValues v = new ContentValues();
        v.put("user_id", userId); v.put("title", title); v.put("body", body);
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("notifications", null, v);
    }

    /** Notifies every student enrolled in the course (SRS FR 9.1). */
    public void notifyCourseStudents(long courseId, String title, String body) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT user_id FROM enrollments WHERE course_id=?",
                new String[]{String.valueOf(courseId)});
        while (c.moveToNext()) notify(c.getLong(0), title, body);
        c.close();
    }

    public List<String[]> notifications(long userId) {
        List<String[]> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT title,body FROM notifications WHERE user_id=? ORDER BY id DESC",
                new String[]{String.valueOf(userId)});
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
                "SELECT COUNT(*) FROM notifications WHERE user_id=? AND id>?",
                new String[]{String.valueOf(userId), String.valueOf(lastSeenId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** Id of the student's newest notification, or 0 when they have none. */
    public long newestNotificationId(long userId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(id) FROM notifications WHERE user_id=?",
                new String[]{String.valueOf(userId)});
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
}
