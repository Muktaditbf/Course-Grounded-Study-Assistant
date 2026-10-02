package com.seu.studyassistant.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns a file the teacher picked into plain text the retrieval engine can index.
 *
 * Covers the formats SRS FR 2.1 names - slides, notes, PDFs and lab manuals - as a PDF,
 * a PowerPoint deck, a Word document, or plain text / markdown. Everything happens on
 * device; nothing is uploaded.
 */
public final class DocumentImporter {

    /** Anything shorter than this is not a usable teaching passage. */
    public static final int MIN_USEFUL_CHARS = 40;

    /**
     * Ceiling on extracted characters. A deck with embedded media can unzip to far more text
     * than a phone wants to hold, and the retrieval index gains nothing past this point.
     */
    private static final int MAX_CHARS = 500000;

    /** OOXML keeps visible text in leaf run elements: a:t inside slides, w:t inside documents. */
    private static final Pattern PPT_RUN = Pattern.compile("<a:t[^>]*>(.*?)</a:t>", Pattern.DOTALL);
    private static final Pattern DOC_RUN = Pattern.compile("<w:t[^>]*>(.*?)</w:t>", Pattern.DOTALL);
    /** Case-insensitive: slides are "slide1.xml" but their notes part is "notesSlide1.xml". */
    private static final Pattern SLIDE_NO =
            Pattern.compile("slide(\\d+)\\.xml$", Pattern.CASE_INSENSITIVE);

    private static boolean pdfBoxReady = false;

    private DocumentImporter() {}

    /** Outcome of one import attempt. */
    public static class Result {
        public final boolean ok;
        public final String text;
        public final String suggestedTitle;
        /** Set when ok is false; already human readable. */
        public final String error;

        private Result(boolean ok, String text, String title, String error) {
            this.ok = ok; this.text = text; this.suggestedTitle = title; this.error = error;
        }
        static Result success(String text, String title) {
            return new Result(true, text, title, null);
        }
        static Result failure(String error) {
            return new Result(false, null, null, error);
        }
    }

    /** pdfbox-android unpacks its font resources on first use; do that once, lazily. */
    private static void initPdfBox(Context c) {
        if (!pdfBoxReady) {
            PDFBoxResourceLoader.init(c.getApplicationContext());
            pdfBoxReady = true;
        }
    }

    public static String displayName(Context context, Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = context.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {
            // Some providers refuse the query; fall through to the path fallback.
        } finally {
            if (c != null) c.close();
        }
        if (name == null) {
            String path = uri.getLastPathSegment();
            name = path == null ? "Imported material"
                                : path.substring(path.lastIndexOf('/') + 1);
        }
        return name;
    }

    /** Strips the extension so the file name can seed the material title. */
    public static String titleFromFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.replace('_', ' ').replace('-', ' ').trim();
    }

    /**
     * Reads the picked document and returns its text.
     * Performs blocking IO, so call it off the main thread.
     */
    public static Result read(Context context, Uri uri, String errUnsupported,
                              String errEmpty, String errUnreadable, String errLegacy) {
        String name = displayName(context, uri);
        String lower = name.toLowerCase();
        ContentResolver cr = context.getContentResolver();
        String mime = cr.getType(uri);

        boolean isPdf = lower.endsWith(".pdf") || (mime != null && mime.contains("pdf"));
        boolean isPptx = lower.endsWith(".pptx")
                || (mime != null && mime.contains("presentationml"));
        boolean isDocx = lower.endsWith(".docx")
                || (mime != null && mime.contains("wordprocessingml"));
        boolean isText = lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".markdown") || lower.endsWith(".csv")
                || (mime != null && mime.startsWith("text/"));

        // The legacy binary formats are a different container entirely, so say so plainly
        // rather than failing later with a generic "could not be read".
        if (lower.endsWith(".ppt") || lower.endsWith(".doc")) return Result.failure(errLegacy);

        if (!isPdf && !isPptx && !isDocx && !isText) return Result.failure(errUnsupported);

        try {
            String text = isPdf ? readPdf(context, cr, uri)
                    : isPptx ? readPptx(cr, uri)
                    : isDocx ? readDocx(cr, uri)
                    : readPlainText(cr, uri);
            text = normalise(text);
            // A scanned PDF is images only: it opens fine but yields no text.
            if (text.length() < MIN_USEFUL_CHARS) return Result.failure(errEmpty);
            return Result.success(text, titleFromFileName(name));
        } catch (OutOfMemoryError e) {
            return Result.failure(errUnreadable);
        } catch (Exception e) {
            return Result.failure(errUnreadable);
        }
    }

    private static String readPdf(Context context, ContentResolver cr, Uri uri) throws IOException {
        initPdfBox(context);
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("cannot open");
        PDDocument doc = null;
        try {
            doc = PDDocument.load(in);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } finally {
            if (doc != null) {
                try { doc.close(); } catch (IOException ignored) { }
            }
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * PowerPoint (.pptx) text.
     *
     * A .pptx is a ZIP of XML parts, so no library is needed - java.util.zip is in the JDK.
     * Slides are pulled out by number rather than in ZIP order, which is arbitrary and would
     * otherwise put slide10 before slide2 and scramble the lecture. A slide's speaker notes
     * are appended to that slide, because notes usually carry the explanation the bullets
     * only gesture at, and that is exactly what makes a good retrieval passage.
     */
    private static String readPptx(ContentResolver cr, Uri uri) throws IOException {
        Map<Integer, String> slides = new TreeMap<>();
        Map<Integer, String> notes = new TreeMap<>();

        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("cannot open");
        ZipInputStream zip = new ZipInputStream(in);
        try {
            ZipEntry e;
            int budget = MAX_CHARS;
            while ((e = zip.getNextEntry()) != null && budget > 0) {
                String n = e.getName();
                boolean isSlide = n.startsWith("ppt/slides/slide") && n.endsWith(".xml");
                boolean isNote = n.startsWith("ppt/notesSlides/notesSlide") && n.endsWith(".xml");
                if (!isSlide && !isNote) continue;

                Matcher m = SLIDE_NO.matcher(n);
                if (!m.find()) continue;
                int index = Integer.parseInt(m.group(1));

                String text = runsToText(readEntry(zip, budget), PPT_RUN, "</a:p>", "\n");
                budget -= text.length();
                if (isSlide) {
                    slides.put(index, text);
                } else {
                    notes.put(index, text);
                }
            }
        } finally {
            try { zip.close(); } catch (IOException ignored) { }
        }

        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, String> slide : slides.entrySet()) {
            String body = slide.getValue().trim();
            String note = notes.containsKey(slide.getKey()) ? notes.get(slide.getKey()).trim() : "";
            if (body.isEmpty() && note.isEmpty()) continue;

            if (out.length() > 0) out.append("\n\n");
            if (!body.isEmpty()) out.append(body);
            if (!note.isEmpty()) {
                if (body.length() > 0) out.append("\n");
                out.append(note);
            }
        }
        return out.toString();
    }

    /** Word (.docx) text. Same ZIP-of-XML idea, but the body is a single part. */
    private static String readDocx(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("cannot open");
        ZipInputStream zip = new ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(e.getName())) continue;
                return runsToText(readEntry(zip, MAX_CHARS), DOC_RUN, "</w:p>", "\n\n");
            }
        } finally {
            try { zip.close(); } catch (IOException ignored) { }
        }
        return "";
    }

    /** Reads the current ZIP entry as UTF-8, stopping once past the character budget. */
    private static String readEntry(ZipInputStream zip, int budget) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        long cap = (long) budget * 4;   // 4 bytes per char is a safe upper bound for this XML
        while ((read = zip.read(chunk)) > 0) {
            buf.write(chunk, 0, read);
            if (buf.size() > cap) break;
        }
        return new String(buf.toByteArray(), Charset.forName("UTF-8"));
    }

    /**
     * Collapses OOXML runs into readable text.
     *
     * Word and PowerPoint split one sentence across many run elements whenever formatting
     * changes mid-line, so runs are joined with nothing between them and the line break is
     * taken at the paragraph boundary instead. Joining runs with spaces would put a gap
     * around every bolded word.
     *
     * {@code join} differs by format, and it decides how the text later chunks. A Word
     * paragraph is its own idea, so documents use a blank line, which normalise() preserves
     * and the chunker treats as a passage boundary. Bullets inside one slide belong together,
     * so decks use a single newline that collapses into the slide's block.
     */
    private static String runsToText(String xml, Pattern run, String paragraphEnd,
                                     String join) {
        StringBuilder out = new StringBuilder();
        for (String para : xml.split(Pattern.quote(paragraphEnd))) {
            StringBuilder line = new StringBuilder();
            Matcher m = run.matcher(para);
            while (m.find()) line.append(unescapeXml(m.group(1)));

            String text = line.toString().trim();
            if (text.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(text);
        }
        return out.toString();
    }

    /** The five predefined XML entities, plus numeric character references. */
    private static String unescapeXml(String raw) {
        if (raw.indexOf('&') < 0) return raw;
        String s = raw.replace("&lt;", "<").replace("&gt;", ">")
                      .replace("&quot;", "\"").replace("&apos;", "'");

        Matcher m = Pattern.compile("&#(x?)([0-9A-Fa-f]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = ' ';
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);

        // Ampersand last, so an escaped "&amp;lt;" does not turn into a literal "<".
        return sb.toString().replace("&amp;", "&");
    }

    private static String readPlainText(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("cannot open");
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, Charset.forName("UTF-8")));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            try { reader.close(); } catch (IOException ignored) { }
        }
        return sb.toString();
    }

    /**
     * Slide decks and lab manuals extract with hard line breaks mid-sentence and runs of blank
     * lines. Collapse that into flowing prose so the sentence-boundary chunker works properly.
     */
    private static String normalise(String raw) {
        if (raw == null) return "";

        // Sentinel that cannot appear in extracted text. It protects genuine paragraph breaks
        // while every soft wrap around it is collapsed to a space.
        final String PARA = String.valueOf((char) 1);
        final char NBSP = (char) 160;

        String t = raw
                .replace(NBSP, ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        t = t.replaceAll("[ \\t]+", " ");
        t = t.replaceAll("\\n{2,}", PARA);
        t = t.replace('\n', ' ');
        t = t.replace(PARA, "\n\n");
        t = t.replaceAll(" {2,}", " ");
        t = t.replaceAll(" *\\n\\n *", "\n\n");
        return t.trim();
    }
}
