package com.seu.studyassistant.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.Blob;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.WriteBatch;
import com.seu.studyassistant.R;
import com.seu.studyassistant.model.User;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything that talks to Firebase: sign-in, and every write that has to reach Firestore.
 *
 * Reads never come through here. SyncManager listens to Firestore and mirrors it into SQLite,
 * and the screens read the mirror. Writes made here are fire-and-forget (Firestore applies them
 * locally at once, queues them offline and retries) except the few where the user needs to know
 * the outcome: sign-up, login, joining a course, creating a course.
 *
 * Document layout (all ids are Firestore ids; user references are Firebase uids):
 *   users/{uid}                     name, email, contact, role, tier
 *   courses/{id}                    code, title, faculty, schedule, joinCode, teacherUid
 *   joinCodes/{CODE}                courseId, courseCode, teacherUid   (one per course)
 *   enrollments/{uid}_{courseId}    userUid, courseId, teacherUid
 *   materials/{id}                  courseId, teacherUid, title, type, body, approved,
 *                                   fileName, fileMime, fileSize, fileChunks, fileVersion
 *   materials/{id}/file/{n}         data (bytes), n - the original upload, in pieces
 *   questions/{id}                  userUid, courseId, teacherUid, text, answered, createdAt
 *   bookmarks/{uid}_{materialId}    userUid, materialId
 *   notifications/{id}              userUid, courseId?, title, body, createdAt
 * Access is enforced by firestore.rules, not by this client.
 */
public final class CloudRepo {

    private static final String TAG = "CloudRepo";

    /**
     * A Firestore document is capped at 1 MiB. Extracted lecture text can exceed that, and a
     * character can take up to three bytes, so the stored text is cut well below the limit.
     */
    static final int MAX_BODY_CHARS = 300_000;

    /**
     * The original file is stored in Firestore itself, split into pieces, which keeps it on the
     * free plan (Cloud Storage now needs a billing account) and under the same security rules
     * as the material. A piece must fit in one 1 MiB document with room for its other fields.
     */
    static final int FILE_CHUNK_BYTES = 900 * 1024;

    /** Files are read and written off the main thread, one at a time, in order. */
    private static final ExecutorService fileIo = Executors.newSingleThreadExecutor();

    private CloudRepo() {}

    /** What the cloud needs to know about an original file. */
    public static final class FileMeta {
        public final String name, mime, version;
        public final long size;
        public final int chunks;

        private FileMeta(String name, String mime, long size, String version) {
            this.name = name; this.mime = mime; this.size = size; this.version = version;
            this.chunks = (int) ((size + FILE_CHUNK_BYTES - 1) / FILE_CHUNK_BYTES);
        }

        /** Null when there is no stored file to share. */
        @Nullable
        public static FileMeta of(@Nullable String path, String name, String mime) {
            if (path == null || name == null) return null;
            File f = new File(path);
            if (!f.exists() || f.length() == 0) return null;
            return new FileMeta(name, mime, f.length(),
                    System.currentTimeMillis() + "-" + Long.toHexString(f.length()));
        }
    }

    private static void putFile(Map<String, Object> m, @Nullable FileMeta file) {
        m.put("fileName", file == null ? null : file.name);
        m.put("fileMime", file == null ? null : file.mime);
        m.put("fileSize", file == null ? 0 : file.size);
        m.put("fileChunks", file == null ? 0 : file.chunks);
        m.put("fileVersion", file == null ? null : file.version);
    }

    /** Writes the file's pieces under materials/{id}/file. Runs on the file thread. */
    private static void uploadFile(final String materialId, final String path, final FileMeta file) {
        fileIo.execute(() -> {
            byte[] buf = new byte[FILE_CHUNK_BYTES];
            try (InputStream in = new FileInputStream(path)) {
                for (int n = 0; n < file.chunks; n++) {
                    int len = 0, r;
                    while (len < buf.length && (r = in.read(buf, len, buf.length - len)) > 0) len += r;
                    byte[] piece = java.util.Arrays.copyOf(buf, len);

                    Map<String, Object> doc = new HashMap<>();
                    doc.put("n", n);
                    doc.put("data", Blob.fromBytes(piece));
                    fs().collection("materials").document(materialId)
                            .collection("file").document(String.valueOf(n)).set(doc)
                            .addOnFailureListener(e -> log("uploadFile", e));
                }
            } catch (IOException e) {
                log("uploadFile", e);
            }
        });
    }

    private static void deletePieces(String materialId, int fromIndex, int toExclusive) {
        if (toExclusive <= fromIndex) return;
        WriteBatch b = fs().batch();
        for (int n = fromIndex; n < toExclusive; n++) {
            b.delete(fs().collection("materials").document(materialId)
                    .collection("file").document(String.valueOf(n)));
        }
        b.commit().addOnFailureListener(e -> log("deleteFile", e));
    }

    /** Reports download progress as pieces arrive, on the main thread. */
    public interface Progress { void onProgress(int done, int total); }

    /**
     * Downloads a material's original into this device's storage, piece by piece, and returns
     * the file. Pieces are fetched one at a time so a large deck never sits in memory whole and
     * the screen can show real progress.
     */
    public static void downloadFile(final Context ctx, final String materialId, final int chunks,
                                    final String version, final String fileName,
                                    final Progress progress, final Callback<File> cb) {
        if (!isConfigured(ctx) || chunks <= 0) { cb.onError(new IllegalStateException("no file")); return; }

        String ext = "";
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0) ext = fileName.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        final File out = new File(FileStore.dir(ctx),
                "r_" + materialId + "_" + (version == null ? "0" : version.replaceAll("[^A-Za-z0-9-]", "")) + ext);
        final File part = new File(out.getPath() + ".part");

        if (out.exists() && out.length() > 0) { cb.onSuccess(out); return; }

        final FileOutputStream os;
        try {
            os = new FileOutputStream(part);
        } catch (IOException e) {
            cb.onError(e);
            return;
        }
        fetchPiece(materialId, 0, chunks, os, part, out, progress, cb);
    }

    private static void fetchPiece(final String materialId, final int n, final int total,
                                   final FileOutputStream os, final File part, final File out,
                                   final Progress progress, final Callback<File> cb) {
        if (n >= total) {
            fileIo.execute(() -> {
                try {
                    os.close();
                    if (out.exists()) out.delete();
                    if (!part.renameTo(out)) throw new IOException("rename failed");
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onSuccess(out));
                } catch (IOException e) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onError(e));
                }
            });
            return;
        }
        fs().collection("materials").document(materialId)
                .collection("file").document(String.valueOf(n)).get()
                .addOnFailureListener(e -> { closeQuietly(os); part.delete(); cb.onError(e); })
                .addOnSuccessListener(doc -> {
                    Blob data = doc.getBlob("data");
                    if (data == null) {
                        // The teacher's upload has not finished reaching the server yet.
                        closeQuietly(os); part.delete();
                        cb.onError(new FileNotReady());
                        return;
                    }
                    final byte[] bytes = data.toBytes();
                    fileIo.execute(() -> {
                        try {
                            os.write(bytes);
                        } catch (IOException e) {
                            closeQuietly(os); part.delete();
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onError(e));
                            return;
                        }
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            progress.onProgress(n + 1, total);
                            fetchPiece(materialId, n + 1, total, os, part, out, progress, cb);
                        });
                    });
                });
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { c.close(); } catch (IOException ignored) { }
    }

    /** The file is listed but its pieces are not all on the server yet. */
    public static final class FileNotReady extends Exception {
        FileNotReady() { super("file still uploading"); }
    }

    // ------------------------------------------------------------------ plumbing

    /** False when google-services.json was not added at build time. */
    public static boolean isConfigured(Context c) {
        return !FirebaseApp.getApps(c.getApplicationContext()).isEmpty();
    }

    private static FirebaseFirestore fs() { return FirebaseFirestore.getInstance(); }
    private static FirebaseAuth auth() { return FirebaseAuth.getInstance(); }

    /** The signed-in Firebase uid, or null. */
    @Nullable
    public static String currentUid(Context c) {
        if (!isConfigured(c)) return null;
        FirebaseUser u = auth().getCurrentUser();
        // An account whose email is not confirmed yet is not signed in to the app.
        return u == null || !u.isEmailVerified() ? null : u.getUid();
    }

    /** The Firebase account, verified or not (the verify-email screen needs the unverified one). */
    @Nullable
    public static FirebaseUser firebaseUser(Context c) {
        return isConfigured(c) ? auth().getCurrentUser() : null;
    }

    /** A fresh document id, generated on the client so the local row can carry it. */
    public static String newId(String collection) {
        return fs().collection(collection).document().getId();
    }

    /**
     * Logs a failed background write AND tells the user. Writes are fire-and-forget, so without
     * this a rejected one (rules not published, no permission) would vanish silently and the
     * teacher would see their change on their own phone and nowhere else.
     */
    private static void log(String what, Exception e) {
        Log.w(TAG, what + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        SyncManager.get().reportError(what + ": " + (isPermissionDenied(e)
                ? "permission denied by Firestore rules" : e.getMessage()));
    }

    /** Maps a sign-in or sign-up failure to the string the user should see. */
    public static int authErrorRes(Exception e) {
        if (e instanceof IllegalStateException && "not configured".equals(e.getMessage())) return R.string.err_firebase_missing;
        if (e instanceof FirebaseAuthUserCollisionException) return R.string.err_email_taken;
        if (e instanceof FirebaseAuthWeakPasswordException) return R.string.err_weak_password;
        if (e instanceof FirebaseAuthInvalidCredentialsException
                || e instanceof FirebaseAuthInvalidUserException) return R.string.err_bad_credentials;
        if (e instanceof FirebaseTooManyRequestsException) return R.string.err_too_many_attempts;
        if (e instanceof FirebaseNetworkException) return R.string.err_network;
        if (e instanceof EmailNotVerified) return R.string.verify_still_pending;
        return R.string.err_generic;
    }

    /** The account exists and the password was right, but its email is not confirmed yet. */
    public static final class EmailNotVerified extends Exception {
        EmailNotVerified() { super("email not verified"); }
    }

    public static boolean isPermissionDenied(Exception e) {
        return e instanceof FirebaseFirestoreException
                && ((FirebaseFirestoreException) e).getCode()
                == FirebaseFirestoreException.Code.PERMISSION_DENIED;
    }

    // ---------------------------------------------------------------------- auth

    /**
     * UC1. Creates the Firebase account and the profile document that carries the role, then
     * emails a verification link. Succeeds with null: the caller shows the verify-email screen.
     */
    public static void signUp(final Context ctx, final String name, final String email,
                              final String contact, String password, final String role,
                              final Callback<User> cb) {
        if (!isConfigured(ctx)) { cb.onError(new IllegalStateException("not configured")); return; }

        auth().createUserWithEmailAndPassword(email, password)
                .addOnFailureListener(cb::onError)
                .addOnSuccessListener(result -> {
                    final FirebaseUser fu = result.getUser();
                    if (fu == null) { cb.onError(new IllegalStateException("no user")); return; }

                    Map<String, Object> profile = new HashMap<>();
                    profile.put("name", name);
                    profile.put("email", email);
                    profile.put("contact", contact);
                    profile.put("role", role);
                    profile.put("tier", "free");

                    fs().collection("users").document(fu.getUid()).set(profile)
                            .addOnSuccessListener(v -> {
                                // Real emails only: the account cannot be used until the link in
                                // this email is opened. Success here means "check your inbox".
                                fu.sendEmailVerification();
                                cb.onSuccess(null);
                            })
                            .addOnFailureListener(e -> {
                                // No profile means no role, so the account is unusable. Remove
                                // it so the email is not left half-registered.
                                fu.delete();
                                auth().signOut();
                                cb.onError(e);
                            });
                });
    }

    /** UC2. Signs in, then loads the profile to learn the role (FR 1.3). */
    public static void login(final Context ctx, String email, String password,
                             final Callback<User> cb) {
        if (!isConfigured(ctx)) { cb.onError(new IllegalStateException("not configured")); return; }

        auth().signInWithEmailAndPassword(email, password)
                .addOnFailureListener(cb::onError)
                .addOnSuccessListener(result -> {
                    FirebaseUser fu = result.getUser();
                    if (fu == null) { cb.onError(new IllegalStateException("no user")); return; }
                    if (!fu.isEmailVerified()) {
                        // Stay signed in to Firebase so the verify screen can resend the link.
                        cb.onError(new EmailNotVerified());
                        return;
                    }
                    loadProfileAndFinish(ctx, fu.getUid(), cb);
                });
    }

    private static void loadProfileAndFinish(final Context ctx, final String uid, final Callback<User> cb) {
        fs().collection("users").document(uid).get()
                .addOnSuccessListener((DocumentSnapshot doc) -> {
                    if (!doc.exists()) {
                        auth().signOut();
                        cb.onError(new IllegalStateException("no profile"));
                        return;
                    }
                    finishSignIn(ctx, uid, doc.getData(), cb);
                })
                .addOnFailureListener(e -> { auth().signOut(); cb.onError(e); });
    }

    // ------------------------------------------------------- email verification

    /**
     * Called from the verify screen after the user opened the link. Reloads the account, and
     * refreshes the ID token so the security rules see email_verified = true straight away.
     */
    public static void continueAfterVerification(final Context ctx, final Callback<User> cb) {
        final FirebaseUser fu = firebaseUser(ctx);
        if (fu == null) { cb.onError(new IllegalStateException("no user")); return; }
        fu.reload()
                .addOnFailureListener(cb::onError)
                .addOnSuccessListener(v -> {
                    FirebaseUser fresh = auth().getCurrentUser();
                    if (fresh == null || !fresh.isEmailVerified()) { cb.onError(new EmailNotVerified()); return; }
                    fresh.getIdToken(true)
                            .addOnFailureListener(cb::onError)
                            .addOnSuccessListener(t -> loadProfileAndFinish(ctx, fresh.getUid(), cb));
                });
    }

    public static void resendVerification(Context ctx, final Callback<Void> cb) {
        FirebaseUser fu = firebaseUser(ctx);
        if (fu == null) { cb.onError(new IllegalStateException("no user")); return; }
        fu.sendEmailVerification()
                .addOnSuccessListener(v -> cb.onSuccess(null))
                .addOnFailureListener(cb::onError);
    }

    // ------------------------------------------------------------ password reset

    /**
     * Emails a password-reset link. Firebase hosts the page where the new password is set.
     * The result never says whether an account exists, so the form cannot be used to probe
     * which emails are registered.
     */
    public static void sendPasswordReset(Context ctx, String email, final Callback<Void> cb) {
        if (!isConfigured(ctx)) { cb.onError(new IllegalStateException("not configured")); return; }
        auth().sendPasswordResetEmail(email.trim())
                .addOnSuccessListener(v -> cb.onSuccess(null))
                .addOnFailureListener(e -> {
                    if (e instanceof FirebaseAuthInvalidUserException) cb.onSuccess(null);   // same answer either way
                    else cb.onError(e);
                });
    }

    /** True when this account has a password (rather than only Google sign-in). */
    public static boolean hasPassword(Context ctx) {
        FirebaseUser fu = firebaseUser(ctx);
        if (fu == null) return false;
        for (UserInfo info : fu.getProviderData()) {
            if (EmailAuthProvider.PROVIDER_ID.equals(info.getProviderId())) return true;
        }
        return false;
    }

    /** Re-checks the current password (Firebase requires a recent sign-in), then changes it. */
    public static void changePassword(Context ctx, String current, final String next, final Callback<Void> cb) {
        final FirebaseUser fu = firebaseUser(ctx);
        if (fu == null || fu.getEmail() == null) { cb.onError(new IllegalStateException("no user")); return; }
        AuthCredential cred = EmailAuthProvider.getCredential(fu.getEmail(), current);
        fu.reauthenticate(cred)
                .addOnFailureListener(cb::onError)
                .addOnSuccessListener(v -> fu.updatePassword(next)
                        .addOnSuccessListener(x -> cb.onSuccess(null))
                        .addOnFailureListener(cb::onError));
    }

    // ------------------------------------------------------------- Google sign-in

    /**
     * Signs in with a Google ID token. Succeeds with the user when a profile already exists,
     * or with null for a first-time Google user, who must then pick a role.
     */
    public static void signInWithGoogle(final Context ctx, String idToken, final Callback<User> cb) {
        if (!isConfigured(ctx)) { cb.onError(new IllegalStateException("not configured")); return; }
        auth().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .addOnFailureListener(cb::onError)
                .addOnSuccessListener(result -> {
                    final FirebaseUser fu = result.getUser();
                    if (fu == null) { cb.onError(new IllegalStateException("no user")); return; }
                    fs().collection("users").document(fu.getUid()).get()
                            .addOnFailureListener(e -> { auth().signOut(); cb.onError(e); })
                            .addOnSuccessListener(doc -> {
                                if (doc.exists()) finishSignIn(ctx, fu.getUid(), doc.getData(), cb);
                                else cb.onSuccess(null);
                            });
                });
    }

    /** Creates the profile for a first-time Google user once they chose a role. */
    public static void createGoogleProfile(final Context ctx, String role, final Callback<User> cb) {
        final FirebaseUser fu = firebaseUser(ctx);
        if (fu == null) { cb.onError(new IllegalStateException("no user")); return; }
        final Map<String, Object> profile = new HashMap<>();
        profile.put("name", fu.getDisplayName() == null ? "" : fu.getDisplayName());
        profile.put("email", fu.getEmail() == null ? "" : fu.getEmail());
        profile.put("contact", fu.getPhoneNumber() == null ? "" : fu.getPhoneNumber());
        profile.put("role", role);
        profile.put("tier", "free");
        fs().collection("users").document(fu.getUid()).set(profile)
                .addOnSuccessListener(v -> finishSignIn(ctx, fu.getUid(), profile, cb))
                .addOnFailureListener(e -> { auth().signOut(); cb.onError(e); });
    }

    private static void finishSignIn(Context ctx, String uid, Map<String, Object> profile,
                                     Callback<User> cb) {
        DatabaseHelper db = DatabaseHelper.get(ctx);
        long localId = db.upsertUser(uid,
                str(profile.get("name")), str(profile.get("email")), str(profile.get("contact")),
                str(profile.get("role")), orDefault(str(profile.get("tier")), "free"));
        new SessionManager(ctx).login(localId);
        SyncManager.get().start(ctx, uid, str(profile.get("role")));
        cb.onSuccess(db.userById(localId));
    }

    /** Stops syncing, signs out of Firebase, and empties the local mirror. */
    public static void signOut(Context ctx) {
        SyncManager.get().stop();
        if (isConfigured(ctx)) auth().signOut();
        GoogleAuth.forgetAccount(ctx);
        DatabaseHelper.get(ctx).clearAll();
    }

    // -------------------------------------------------------------------- courses

    /**
     * FR 2.1. One batch: the course, and the join code that points at it. The rules refuse a
     * join-code document that already exists, so a taken code fails the whole batch with
     * PERMISSION_DENIED and no half-created course is left behind.
     */
    public static void createCourse(Context ctx, String teacherUid, String code, String title,
                                    String faculty, String schedule, String joinCode,
                                    Callback<String> cb) {
        String courseId = newId("courses");
        writeCourse(fs().batch(), courseId, teacherUid, code, title, faculty, schedule, joinCode)
                .commit()
                .addOnSuccessListener(v -> cb.onSuccess(courseId))
                .addOnFailureListener(cb::onError);
    }

    private static WriteBatch writeCourse(WriteBatch batch, String courseId, String teacherUid,
                                          String code, String title, String faculty,
                                          String schedule, String joinCode) {
        Map<String, Object> course = new HashMap<>();
        course.put("code", code);
        course.put("title", title);
        course.put("faculty", faculty);
        course.put("schedule", schedule);
        course.put("joinCode", joinCode);
        course.put("teacherUid", teacherUid);
        batch.set(fs().collection("courses").document(courseId), course);

        Map<String, Object> jc = new HashMap<>();
        jc.put("courseId", courseId);
        jc.put("courseCode", code);
        jc.put("teacherUid", teacherUid);
        batch.set(fs().collection("joinCodes").document(joinCode), jc);
        return batch;
    }

    /**
     * Enrols the student through a join code. Looks the code up, then writes the enrolment.
     * Succeeds with the course code (for the confirmation toast), or fails with
     * {@link CodeNotFound} when no course uses the code.
     */
    public static void joinByCode(Context ctx, String studentUid, String rawCode,
                                  final Callback<String> cb) {
        final String code = DatabaseHelper.normaliseJoinCode(rawCode);
        if (code.isEmpty() || code.contains("/")) { cb.onError(new CodeNotFound()); return; }

        fs().collection("joinCodes").document(code).get()
                .addOnFailureListener(cb::onError)
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) { cb.onError(new CodeNotFound()); return; }
                    final String courseId = str(doc.get("courseId"));
                    final String courseCode = str(doc.get("courseCode"));

                    Map<String, Object> enrol = new HashMap<>();
                    enrol.put("userUid", studentUid);
                    enrol.put("courseId", courseId);
                    enrol.put("teacherUid", str(doc.get("teacherUid")));
                    fs().collection("enrollments").document(studentUid + "_" + courseId).set(enrol)
                            .addOnSuccessListener(v -> cb.onSuccess(courseCode))
                            .addOnFailureListener(cb::onError);
                });
    }

    /** Thrown to the join callback when a join code matches no course. */
    public static final class CodeNotFound extends Exception {
        CodeNotFound() { super("join code not found"); }
    }

    // ------------------------------------------------------------ fire-and-forget

    static void updateTier(Context ctx, String uid, String tier) {
        if (!isConfigured(ctx)) return;
        fs().collection("users").document(uid).update("tier", tier)
                .addOnFailureListener(e -> log("updateTier", e));
    }

    static void pushMaterial(Context ctx, String remoteId, String courseRemoteId,
                             String teacherUid, String title, String type, String body,
                             boolean approved, @Nullable FileMeta file, @Nullable String filePath) {
        if (!isConfigured(ctx)) return;
        Map<String, Object> m = new HashMap<>();
        m.put("courseId", courseRemoteId);
        m.put("teacherUid", teacherUid);
        m.put("title", title);
        m.put("type", type);
        m.put("body", body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body);
        m.put("approved", approved);
        m.put("createdAt", System.currentTimeMillis());
        putFile(m, file);
        // The material document first: the rules for its file pieces check it exists.
        fs().collection("materials").document(remoteId).set(m)
                .addOnFailureListener(e -> log("pushMaterial", e));
        if (file != null && filePath != null) uploadFile(remoteId, filePath, file);
    }

    static void setApproved(Context ctx, String materialRemoteId, boolean approved) {
        if (!isConfigured(ctx)) return;
        fs().collection("materials").document(materialRemoteId).update("approved", approved)
                .addOnFailureListener(e -> log("setApproved", e));
    }

    static void pushQuestion(Context ctx, String remoteId, String userUid, String courseRemoteId,
                             String teacherUid, String text, boolean answered, long createdAt) {
        if (!isConfigured(ctx)) return;
        Map<String, Object> q = new HashMap<>();
        q.put("userUid", userUid);
        q.put("courseId", courseRemoteId);
        q.put("teacherUid", teacherUid);
        q.put("text", text);
        q.put("answered", answered);
        q.put("createdAt", createdAt);
        fs().collection("questions").document(remoteId).set(q)
                .addOnFailureListener(e -> log("pushQuestion", e));
    }

    static void pushBookmark(Context ctx, String docId, String userUid, String materialRemoteId) {
        if (!isConfigured(ctx)) return;
        Map<String, Object> b = new HashMap<>();
        b.put("userUid", userUid);
        b.put("materialId", materialRemoteId);
        fs().collection("bookmarks").document(docId).set(b)
                .addOnFailureListener(e -> log("pushBookmark", e));
    }

    static void deleteBookmark(Context ctx, String docId) {
        if (!isConfigured(ctx)) return;
        fs().collection("bookmarks").document(docId).delete()
                .addOnFailureListener(e -> log("deleteBookmark", e));
    }

    /** @param courseRemoteId set when a teacher notifies a student, so the rules can verify it */
    static void pushNotification(Context ctx, String remoteId, String userUid,
                                 @Nullable String courseRemoteId, String title, String body,
                                 long createdAt) {
        if (!isConfigured(ctx)) return;
        Map<String, Object> n = new HashMap<>();
        n.put("userUid", userUid);
        if (courseRemoteId != null) n.put("courseId", courseRemoteId);
        n.put("title", title);
        n.put("body", body);
        n.put("createdAt", createdAt);
        fs().collection("notifications").document(remoteId).set(n)
                .addOnFailureListener(e -> log("pushNotification", e));
    }

    // --------------------------------------------------------------- edit and delete

    static void updateCourse(Context ctx, String courseRemoteId, String code, String title,
                             String faculty, String schedule) {
        if (!isConfigured(ctx)) return;
        Map<String, Object> f = new HashMap<>();
        f.put("code", code);
        f.put("title", title);
        f.put("faculty", faculty);
        f.put("schedule", schedule);
        fs().collection("courses").document(courseRemoteId).update(f)
                .addOnFailureListener(e -> log("updateCourse", e));
        // The join-code lookup shows the course code in the "Joined ..." confirmation.
        // It is only a display label, so a failure here is not worth reporting.
    }

    static void updateMaterial(Context ctx, String remoteId, String title, String type,
                               String body, boolean replaceFile, @Nullable FileMeta file,
                               @Nullable String filePath, int oldChunks) {
        if (!isConfigured(ctx)) return;
        Map<String, Object> f = new HashMap<>();
        f.put("title", title);
        f.put("type", type);
        f.put("body", body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body);
        if (replaceFile) putFile(f, file);
        fs().collection("materials").document(remoteId).update(f)
                .addOnFailureListener(e -> log("updateMaterial", e));

        if (replaceFile) {
            // New pieces overwrite the old ones by index; any left over past the new count go.
            int newChunks = file == null ? 0 : file.chunks;
            if (file != null && filePath != null) uploadFile(remoteId, filePath, file);
            deletePieces(remoteId, newChunks, oldChunks);
        }
    }

    /** Pieces first: their rules read the material document, which must still exist. */
    static void deleteMaterial(Context ctx, final String remoteId, int chunks) {
        if (!isConfigured(ctx)) return;
        WriteBatch b = fs().batch();
        for (int n = 0; n < chunks; n++) {
            b.delete(fs().collection("materials").document(remoteId)
                    .collection("file").document(String.valueOf(n)));
        }
        b.commit().addOnCompleteListener(t ->
                fs().collection("materials").document(remoteId).delete()
                        .addOnFailureListener(e -> log("deleteMaterial", e)));
    }

    /**
     * Deletes a course and everything that hangs off it. Firestore has no cascading delete, so
     * the teacher's materials, enrolments and questions for this course are looked up and
     * removed in batches first; the join code and the course document go in the last batch, so
     * a failure part-way leaves a course that can simply be deleted again.
     */
    static void deleteCourse(final Context ctx, final String teacherUid, final String courseId,
                             final String joinCode, final Callback<Void> cb) {
        if (!isConfigured(ctx)) { cb.onError(new IllegalStateException("not configured")); return; }

        final String[] collections = {"materials", "enrollments", "questions"};
        final List<DocumentReference> refs = new ArrayList<>();
        final int[] pending = {collections.length};
        final boolean[] failed = {false};

        for (String col : collections) {
            // Both constraints, so the query is provably within what the rules allow.
            fs().collection(col)
                    .whereEqualTo("teacherUid", teacherUid)
                    .whereEqualTo("courseId", courseId)
                    .get()
                    .addOnSuccessListener(snap -> {
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            if ("materials".equals(col)) {
                                // The file pieces must go before the material their rules read.
                                Long chunks = d.getLong("fileChunks");
                                for (int n = 0; chunks != null && n < chunks; n++) {
                                    refs.add(0, d.getReference().collection("file")
                                            .document(String.valueOf(n)));
                                }
                            }
                            refs.add(d.getReference());
                        }
                        if (--pending[0] == 0 && !failed[0]) commitCourseDelete(refs, courseId, joinCode, cb);
                    })
                    .addOnFailureListener(e -> {
                        if (!failed[0]) { failed[0] = true; cb.onError(e); }
                    });
        }
    }

    private static void commitCourseDelete(List<DocumentReference> refs, String courseId,
                                           String joinCode, Callback<Void> cb) {
        // The final two deletes are kept for the last batch.
        List<DocumentReference> last = new ArrayList<>();
        if (joinCode != null && !joinCode.isEmpty()) {
            last.add(fs().collection("joinCodes").document(joinCode));
        }
        last.add(fs().collection("courses").document(courseId));

        List<List<DocumentReference>> batches = new ArrayList<>();
        final int size = 400;   // Firestore allows 500 writes per batch
        for (int i = 0; i < refs.size(); i += size) {
            batches.add(refs.subList(i, Math.min(refs.size(), i + size)));
        }
        batches.add(last);
        commitBatches(batches, 0, cb);
    }

    private static void commitBatches(final List<List<DocumentReference>> batches, final int i,
                                      final Callback<Void> cb) {
        if (i >= batches.size()) { cb.onSuccess(null); return; }
        WriteBatch b = fs().batch();
        for (DocumentReference r : batches.get(i)) b.delete(r);
        b.commit()
                .addOnSuccessListener(v -> commitBatches(batches, i + 1, cb))
                .addOnFailureListener(cb::onError);
    }

    // --------------------------------------------------------------------- helpers

    private static String str(@Nullable Object o) { return o == null ? "" : String.valueOf(o); }
    private static String orDefault(String s, String d) { return s.isEmpty() ? d : s; }
}
