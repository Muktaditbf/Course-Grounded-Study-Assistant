package com.seu.studyassistant.data;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Keeps the teacher's original upload on the device.
 *
 * The retrieval engine only ever needs the extracted text, but a student reading a lecture
 * wants the real document - the slides, the diagrams, the layout - not a stripped transcript.
 * Files are copied into the app's own storage rather than referenced by their picker Uri,
 * because that permission is not durable: it dies with the activity, so a bookmark opened
 * next week would otherwise fail with a SecurityException.
 */
public final class FileStore {

    private static final String DIR = "materials";

    /**
     * Refuses anything larger than this. Originals are shared through Firestore's free tier
     * (1 GiB in total), so one upload is kept to a size a whole course of decks can afford.
     */
    public static final long MAX_BYTES = 15L * 1024 * 1024;

    private FileStore() {}

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * Copies the picked document into app storage.
     * Performs blocking IO, so call it off the main thread.
     *
     * @return the stored file's absolute path, or null when it could not be copied
     */
    public static String save(Context context, Uri uri, String displayName) {
        ContentResolver cr = context.getContentResolver();
        String safe = sanitise(displayName);
        File out = new File(dir(context), System.currentTimeMillis() + "_" + safe);

        InputStream in = null;
        OutputStream os = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return null;
            os = new FileOutputStream(out);

            byte[] buf = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buf)) > 0) {
                total += read;
                if (total > MAX_BYTES) {          // give up rather than fill the disk
                    close(os);
                    if (out.exists() && !out.delete()) out.deleteOnExit();
                    return null;
                }
                os.write(buf, 0, read);
            }
            os.flush();
            return out.getAbsolutePath();

        } catch (Exception e) {
            if (out.exists() && !out.delete()) out.deleteOnExit();
            return null;
        } finally {
            close(in);
            close(os);
        }
    }

    /** Deletes a stored upload. Quietly does nothing for a null or missing path. */
    public static void delete(String path) {
        if (path == null) return;
        File f = new File(path);
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }

    /** Strips path separators so a crafted file name cannot escape the materials directory. */
    private static String sanitise(String name) {
        if (name == null || name.trim().isEmpty()) return "upload";
        String s = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.length() > 60 ? s.substring(s.length() - 60) : s;
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }
}
