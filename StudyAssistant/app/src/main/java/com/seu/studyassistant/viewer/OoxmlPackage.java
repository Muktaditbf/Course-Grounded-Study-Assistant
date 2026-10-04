package com.seu.studyassistant.viewer;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Random access to the parts of a .pptx or .docx (both are ZIP files of XML parts), with
 * relationship lookup and a cache of parsed parts.
 */
final class OoxmlPackage implements Closeable {

    /** A single part larger than this is refused, so a hostile file cannot exhaust memory. */
    private static final long MAX_PART_BYTES = 40L * 1024 * 1024;

    private final ZipFile zip;
    private final Map<String, XNode> xmlCache = new HashMap<>();
    private final Map<String, Map<String, Rel>> relCache = new HashMap<>();

    static final class Rel {
        final String type, target;
        Rel(String type, String target) { this.type = type; this.target = target; }
    }

    OoxmlPackage(File file) throws IOException {
        zip = new ZipFile(file);
    }

    boolean has(String part) { return zip.getEntry(part) != null; }

    synchronized XNode xml(String part) {
        if (xmlCache.containsKey(part)) return xmlCache.get(part);
        XNode n = null;
        ZipEntry e = zip.getEntry(part);
        if (e != null && e.getSize() <= MAX_PART_BYTES) {
            try (InputStream in = zip.getInputStream(e)) {
                n = XNode.parse(in);
            } catch (Exception ignored) {
                // A malformed part renders as missing rather than failing the whole document.
            }
        }
        xmlCache.put(part, n);
        return n;
    }

    synchronized byte[] bytes(String part) {
        ZipEntry e = zip.getEntry(part);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > MAX_PART_BYTES) return null;
            }
            return out.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    /** Relationships of a part, by id. Targets are resolved to full part names. */
    synchronized Map<String, Rel> rels(String part) {
        Map<String, Rel> cached = relCache.get(part);
        if (cached != null) return cached;

        Map<String, Rel> out = new HashMap<>();
        int slash = part.lastIndexOf('/');
        String dir = slash < 0 ? "" : part.substring(0, slash);
        String relsPart = (dir.isEmpty() ? "" : dir + "/") + "_rels/" + part.substring(slash + 1) + ".rels";
        XNode root = xml(relsPart);
        if (root != null) {
            for (XNode r : root.all("Relationship")) {
                if ("External".equals(r.attr("TargetMode"))) continue;
                String target = r.attr("Target", "");
                out.put(r.attr("Id", ""), new Rel(r.attr("Type", ""), resolve(dir, target)));
            }
        }
        relCache.put(part, out);
        return out;
    }

    /** First relationship of a part whose type ends with this suffix (e.g. "/slideLayout"). */
    String relByType(String part, String typeSuffix) {
        for (Rel r : rels(part).values()) {
            if (r.type.endsWith(typeSuffix)) return r.target;
        }
        return null;
    }

    String relTarget(String part, String id) {
        if (id == null) return null;
        Rel r = rels(part).get(id);
        return r == null ? null : r.target;
    }

    /** Joins a relative target onto a directory and normalises "..". */
    static String resolve(String dir, String target) {
        if (target.startsWith("/")) return target.substring(1);
        Deque<String> parts = new ArrayDeque<>();
        if (!dir.isEmpty()) for (String s : dir.split("/")) parts.addLast(s);
        for (String s : target.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
            else parts.addLast(s);
        }
        StringBuilder b = new StringBuilder();
        for (String s : parts) { if (b.length() > 0) b.append('/'); b.append(s); }
        return b.toString();
    }

    @Override
    public void close() throws IOException { zip.close(); }
}
