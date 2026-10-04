package com.seu.studyassistant.viewer;

import android.util.Base64;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a Word (.docx) document into one self-contained HTML page for an in-app WebView.
 *
 * Keeps what makes a document read like the original: headings and the document's own
 * paragraph styles, bold/italic/underline/strike, font sizes and colours, highlights,
 * alignment and indents, bulleted and numbered lists, tables with merged columns and cell
 * shading, page breaks, and embedded pictures (inlined as data URIs, so the page needs no
 * file access). Headers, footers, footnotes, comments and text boxes are left out.
 */
public final class DocxHtml {

    private final OoxmlPackage pkg;
    private final Map<String, XNode> styles = new HashMap<>();
    private final Map<String, String> numToAbstract = new HashMap<>();
    private final Map<String, XNode> abstracts = new HashMap<>();
    /** Running counters per list and level, for numbered lists. */
    private final Map<String, int[]> counters = new HashMap<>();
    private final StringBuilder html = new StringBuilder(64 * 1024);

    /** Embedded pictures are capped in total, so a photo-heavy file cannot exhaust memory. */
    private long imageBudget = 30L * 1024 * 1024;

    private static final String DOC = "word/document.xml";

    private DocxHtml(File file) throws IOException {
        pkg = new OoxmlPackage(file);
    }

    /** Builds the page. Blocking: call off the main thread. */
    public static String convert(File file) throws IOException {
        DocxHtml d = new DocxHtml(file);
        try {
            return d.run();
        } finally {
            try { d.pkg.close(); } catch (IOException ignored) { }
        }
    }

    private String run() throws IOException {
        XNode doc = pkg.xml(DOC);
        XNode body = doc == null ? null : doc.child("body");
        if (body == null) throw new IOException("not a Word document");

        loadStyles();
        loadNumbering();

        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
            .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
            .append("<style>")
            .append("html{background:#e9eaef}")
            .append("body{margin:0;padding:12px;font-family:sans-serif;color:#1d1d1f;}")
            .append(".page{background:#fff;max-width:820px;margin:0 auto;padding:28px 24px;")
            .append("box-shadow:0 1px 3px rgba(0,0,0,.15);border-radius:6px;font-size:11pt;line-height:1.45;")
            .append("overflow-wrap:break-word;}")
            .append("p{margin:0 0 8px;white-space:pre-wrap;}")
            .append("h1,h2,h3,h4,h5,h6{margin:16px 0 8px;line-height:1.25;white-space:pre-wrap;}")
            .append("h1{font-size:20pt}h2{font-size:16pt}h3{font-size:13.5pt}h4,h5,h6{font-size:12pt}")
            .append(".title{font-size:26pt;font-weight:600;margin:8px 0 12px}")
            .append(".subtitle{font-size:14pt;color:#595959;margin:0 0 12px}")
            .append("table{border-collapse:collapse;width:100%;margin:8px 0 12px;font-size:10pt}")
            .append("td{border:1px solid #bfbfbf;padding:4px 6px;vertical-align:top}")
            .append("td p{margin:0 0 2px}")
            .append("img{max-width:100%;height:auto}")
            .append(".li{display:block}.mk{display:inline-block;min-width:1.2em;text-indent:0}")
            .append("hr.pb{border:0;border-top:1px dashed #c8c8c8;margin:20px 0}")
            .append("</style></head><body><div class='page'>");

        blocks(body);
        html.append("</div></body></html>");
        return html.toString();
    }

    // ================================================================== blocks

    private void blocks(XNode container) {
        for (XNode n : container.children) {
            switch (n.name) {
                case "p": paragraph(n); break;
                case "tbl": table(n); break;
                case "sdt": {
                    XNode content = n.child("sdtContent");
                    if (content != null) blocks(content);
                    break;
                }
                case "customXml": blocks(n); break;
                default: break;
            }
        }
    }

    private void paragraph(XNode p) {
        XNode pPr = p.child("pPr");
        String styleId = pPr == null || pPr.child("pStyle") == null ? null : pPr.child("pStyle").attr("val");
        XNode style = styleId == null ? null : styles.get(styleId);
        String styleName = style == null || style.child("name") == null ? ""
                : style.child("name").attr("val", "").toLowerCase(Locale.ROOT);

        String tag = "p", cls = null;
        if (styleName.equals("title")) cls = "title";
        else if (styleName.equals("subtitle")) cls = "subtitle";
        else if (styleName.startsWith("heading ")) {
            int level = parseInt(styleName.substring(8).trim(), 0);
            if (level >= 1 && level <= 6) tag = "h" + level;
        } else {
            XNode outline = propFromStyles(pPr, style, "outlineLvl");
            if (outline != null) {
                int level = parseInt(outline.attr("val"), 9) + 1;
                if (level >= 1 && level <= 6) tag = "h" + level;
            }
        }

        StringBuilder css = new StringBuilder();
        XNode jc = propFromStyles(pPr, style, "jc");
        if (jc != null) {
            String v = jc.attr("val", "");
            if (v.equals("center")) css.append("text-align:center;");
            else if (v.equals("right") || v.equals("end")) css.append("text-align:right;");
            else if (v.equals("both") || v.equals("distribute")) css.append("text-align:justify;");
        }
        XNode ind = propFromStyles(pPr, style, "ind");
        int leftTw = 0, hangingTw = 0;
        if (ind != null) {
            leftTw = parseInt(ind.attr("left", ind.attr("start", "0")), 0);
            int hanging = parseInt(ind.attr("hanging", "0"), 0);
            hangingTw = hanging;
            int first = parseInt(ind.attr("firstLine", "0"), 0);
            if (first != 0) css.append("text-indent:").append(twipsToPx(first)).append("px;");
            if (hanging != 0) css.append("text-indent:-").append(twipsToPx(hanging)).append("px;");
        }
        XNode shd = pPr == null ? null : pPr.child("shd");
        String fill = shd == null ? null : shd.attr("fill");
        if (fill != null && fill.matches("[0-9A-Fa-f]{6}")) css.append("background:#").append(fill).append(';');

        // List marker, from numbering.xml.
        String marker = null;
        XNode numPr = propFromStyles(pPr, style, "numPr");
        if (numPr != null) {
            String numId = numPr.child("numId") == null ? null : numPr.child("numId").attr("val");
            int ilvl = numPr.child("ilvl") == null ? 0 : parseInt(numPr.child("ilvl").attr("val"), 0);
            if (numId != null && !"0".equals(numId)) {
                marker = listMarker(numId, ilvl);
                if (leftTw == 0) leftTw = 360 * (ilvl + 1);
            }
        }
        if (leftTw > 0) css.append("padding-left:").append(twipsToPx(leftTw)).append("px;");

        StringBuilder inner = new StringBuilder();
        XNode pbb = pPr == null ? null : pPr.child("pageBreakBefore");
        String pbbVal = pbb == null ? null : pbb.attr("val", "1");
        boolean pageBreakBefore = pbb != null && !("0".equals(pbbVal) || "false".equals(pbbVal));
        inlines(p, style, inner);
        if (pageBreakBefore) html.append("<hr class='pb'>");

        html.append('<').append(tag);
        if (cls != null) html.append(" class='").append(cls).append('\'');
        if (css.length() > 0) html.append(" style='").append(css).append('\'');
        html.append('>');
        if (marker != null) {
            // With a hanging indent the marker fills exactly the hang, so wrapped lines align
            // under the text rather than under the bullet, as in Word.
            html.append("<span class='mk'");
            if (hangingTw > 0) html.append(" style='width:").append(twipsToPx(hangingTw)).append("px'");
            html.append('>').append(escape(marker)).append("</span>");
        }
        if (inner.length() == 0) html.append("&#8203;");   // keep empty lines as spacing
        html.append(inner).append("</").append(tag).append('>');
    }

    /** Runs, links, insertions and fields inside a paragraph. */
    private void inlines(XNode container, XNode paraStyle, StringBuilder out) {
        for (XNode n : container.children) {
            switch (n.name) {
                case "r": run(n, paraStyle, out); break;
                case "hyperlink": case "ins": case "smartTag": case "fldSimple":
                case "customXml": case "sdtContent":
                    inlines(n, paraStyle, out);
                    break;
                case "sdt": {
                    XNode c = n.child("sdtContent");
                    if (c != null) inlines(c, paraStyle, out);
                    break;
                }
                default: break;   // del (tracked deletions), bookmarks, proofing marks
            }
        }
    }

    private void run(XNode r, XNode paraStyle, StringBuilder out) {
        XNode rPr = r.child("rPr");
        XNode runStyle = null;
        if (rPr != null && rPr.child("rStyle") != null) runStyle = styles.get(rPr.child("rStyle").attr("val"));

        StringBuilder css = new StringBuilder();
        if (on(rPr, runStyle, paraStyle, "b")) css.append("font-weight:bold;");
        if (on(rPr, runStyle, paraStyle, "i")) css.append("font-style:italic;");
        XNode u = runProp(rPr, runStyle, paraStyle, "u");
        boolean underline = u != null && !"none".equals(u.attr("val", "single"));
        boolean strike = on(rPr, runStyle, paraStyle, "strike") || on(rPr, runStyle, paraStyle, "dstrike");
        if (underline && strike) css.append("text-decoration:underline line-through;");
        else if (underline) css.append("text-decoration:underline;");
        else if (strike) css.append("text-decoration:line-through;");

        XNode sz = runProp(rPr, runStyle, paraStyle, "sz");
        if (sz != null) {
            int half = parseInt(sz.attr("val"), 0);
            if (half > 0) css.append("font-size:").append(half / 2f).append("pt;");
        }
        XNode color = runProp(rPr, runStyle, paraStyle, "color");
        if (color != null) {
            String v = color.attr("val", "");
            if (v.matches("[0-9A-Fa-f]{6}")) css.append("color:#").append(v).append(';');
        }
        XNode hl = rPr == null ? null : rPr.child("highlight");
        if (hl != null) {
            String c = highlight(hl.attr("val", ""));
            if (c != null) css.append("background:").append(c).append(';');
        }
        XNode va = rPr == null ? null : rPr.child("vertAlign");
        String vaTag = va == null ? null
                : "superscript".equals(va.attr("val")) ? "sup" : "subscript".equals(va.attr("val")) ? "sub" : null;
        boolean caps = on(rPr, runStyle, paraStyle, "caps");
        if (caps) css.append("text-transform:uppercase;");

        StringBuilder text = new StringBuilder();
        for (XNode c : r.children) {
            switch (c.name) {
                case "t": text.append(escape(c.text())); break;
                case "tab": text.append('\t'); break;
                case "br":
                    if ("page".equals(c.attr("type"))) text.append("<hr class='pb'>");
                    else text.append("<br>");
                    break;
                case "cr": text.append("<br>"); break;
                case "noBreakHyphen": text.append("&#8209;"); break;
                case "drawing": image(c.find("blip"), c.find("extent"), text); break;
                case "pict": {
                    XNode img = c.find("imagedata");
                    if (img != null) imageById(img.attr("r:id") != null ? img.attr("r:id") : img.attr("id"), 0, 0, text);
                    break;
                }
                case "AlternateContent": {
                    XNode alt = c.child("Fallback") != null ? c.child("Fallback") : c.child("Choice");
                    if (alt != null) {
                        XNode blip = alt.find("blip");
                        if (blip != null) image(blip, alt.find("extent"), text);
                    }
                    break;
                }
                default: break;
            }
        }
        if (text.length() == 0) return;

        if (vaTag != null) out.append('<').append(vaTag).append('>');
        if (css.length() > 0) out.append("<span style='").append(css).append("'>");
        out.append(text);
        if (css.length() > 0) out.append("</span>");
        if (vaTag != null) out.append("</").append(vaTag).append('>');
    }

    // ================================================================== tables

    private void table(XNode tbl) {
        html.append("<table>");
        for (XNode tr : tbl.all("tr")) {
            html.append("<tr>");
            for (XNode tc : tr.all("tc")) {
                XNode tcPr = tc.child("tcPr");
                // A vertically merged continuation cell is drawn empty with no top border.
                boolean vCont = tcPr != null && tcPr.child("vMerge") != null
                        && !"restart".equals(tcPr.child("vMerge").attr("val"));
                int span = tcPr == null || tcPr.child("gridSpan") == null ? 1
                        : Math.max(1, parseInt(tcPr.child("gridSpan").attr("val"), 1));

                StringBuilder css = new StringBuilder();
                XNode shd = tcPr == null ? null : tcPr.child("shd");
                String fill = shd == null ? null : shd.attr("fill");
                if (fill != null && fill.matches("[0-9A-Fa-f]{6}")) css.append("background:#").append(fill).append(';');
                if (vCont) css.append("border-top:0;");

                html.append("<td");
                if (span > 1) html.append(" colspan='").append(span).append('\'');
                if (css.length() > 0) html.append(" style='").append(css).append('\'');
                html.append('>');
                if (!vCont) blocks(tc);
                html.append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
    }

    // ================================================================== images

    private void image(XNode blip, XNode extent, StringBuilder out) {
        if (blip == null) return;
        String id = blip.attr("r:embed") != null ? blip.attr("r:embed") : blip.attr("embed");
        long cx = extent == null ? 0 : extent.attrLong("cx", 0);
        long cy = extent == null ? 0 : extent.attrLong("cy", 0);
        imageById(id, cx, cy, out);
    }

    private void imageById(String relId, long cx, long cy, StringBuilder out) {
        String target = pkg.relTarget(DOC, relId);
        if (target == null) return;
        String lower = target.toLowerCase(Locale.ROOT);
        String mime = lower.endsWith(".png") ? "image/png"
                : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg"
                : lower.endsWith(".gif") ? "image/gif"
                : lower.endsWith(".bmp") ? "image/bmp"
                : lower.endsWith(".webp") ? "image/webp"
                : lower.endsWith(".svg") ? "image/svg+xml" : null;
        if (mime == null) return;   // EMF/WMF cannot be shown by a WebView

        byte[] data = pkg.bytes(target);
        if (data == null || data.length > imageBudget) return;
        imageBudget -= data.length;

        out.append("<img src='data:").append(mime).append(";base64,")
           .append(Base64.encodeToString(data, Base64.NO_WRAP)).append('\'');
        if (cx > 0) {
            // EMU to CSS pixels (96 per inch); max-width keeps it on screen.
            out.append(" style='width:").append(cx / 9525).append("px'");
        }
        out.append(" alt=''>");
    }

    // ================================================================== lists

    private String listMarker(String numId, int ilvl) {
        String abs = numToAbstract.get(numId);
        XNode an = abs == null ? null : abstracts.get(abs);
        XNode lvl = null;
        if (an != null) {
            for (XNode l : an.all("lvl")) {
                if (parseInt(l.attr("ilvl"), -1) == ilvl) { lvl = l; break; }
            }
        }
        String fmt = lvl == null || lvl.child("numFmt") == null ? "bullet" : lvl.child("numFmt").attr("val", "bullet");
        if ("bullet".equals(fmt)) {
            String t = lvl == null || lvl.child("lvlText") == null ? "" : lvl.child("lvlText").attr("val", "");
            // Symbol-font bullets are private-use characters that render as boxes; normalise them.
            if (t.isEmpty() || t.charAt(0) >= 0xF000) return ilvl % 3 == 0 ? "•" : ilvl % 3 == 1 ? "◦" : "▪";
            return t;
        }
        if ("none".equals(fmt)) return null;

        int[] c = counters.get(numId);
        if (c == null) { c = new int[9]; counters.put(numId, c); }
        if (ilvl < 0 || ilvl > 8) ilvl = 0;
        c[ilvl]++;
        for (int k = ilvl + 1; k < 9; k++) c[k] = 0;

        String text = lvl == null || lvl.child("lvlText") == null ? "%" + (ilvl + 1) + "." : lvl.child("lvlText").attr("val", "%1.");
        for (int k = 0; k <= ilvl; k++) {
            text = text.replace("%" + (k + 1), format(Math.max(1, c[k]), k == ilvl ? fmt : "decimal"));
        }
        return text;
    }

    private static String format(int n, String fmt) {
        switch (fmt) {
            case "lowerLetter": return String.valueOf((char) ('a' + (n - 1) % 26));
            case "upperLetter": return String.valueOf((char) ('A' + (n - 1) % 26));
            case "lowerRoman": return roman(n).toLowerCase(Locale.ROOT);
            case "upperRoman": return roman(n);
            default: return String.valueOf(n);
        }
    }

    private static String roman(int n) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { b.append(s[i]); n -= v[i]; }
        return b.toString();
    }

    // ================================================================== styles

    private void loadStyles() {
        XNode root = pkg.xml("word/styles.xml");
        if (root == null) return;
        for (XNode s : root.all("style")) {
            String id = s.attr("styleId");
            if (id != null) styles.put(id, s);
        }
    }

    private void loadNumbering() {
        XNode root = pkg.xml("word/numbering.xml");
        if (root == null) return;
        for (XNode a : root.all("abstractNum")) abstracts.put(a.attr("abstractNumId", ""), a);
        for (XNode n : root.all("num")) {
            XNode abs = n.child("abstractNumId");
            if (abs != null) numToAbstract.put(n.attr("numId", ""), abs.attr("val", ""));
        }
    }

    /** A paragraph property from the paragraph itself, then its style chain (basedOn). */
    private XNode propFromStyles(XNode pPr, XNode style, String name) {
        if (pPr != null && pPr.child(name) != null) return pPr.child(name);
        XNode s = style;
        for (int depth = 0; s != null && depth < 8; depth++) {
            XNode p = s.path("pPr", name);
            if (p != null) return p;
            s = basedOn(s);
        }
        return null;
    }

    /** A run property from the run, its character style, then the paragraph's style chain. */
    private XNode runProp(XNode rPr, XNode runStyle, XNode paraStyle, String name) {
        if (rPr != null && rPr.child(name) != null) return rPr.child(name);
        for (XNode start : new XNode[]{runStyle, paraStyle}) {
            XNode s = start;
            for (int depth = 0; s != null && depth < 8; depth++) {
                XNode p = s.path("rPr", name);
                if (p != null) return p;
                s = basedOn(s);
            }
        }
        return null;
    }

    private boolean on(XNode rPr, XNode runStyle, XNode paraStyle, String name) {
        XNode n = runProp(rPr, runStyle, paraStyle, name);
        if (n == null) return false;
        String v = n.attr("val");
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("none"));
    }

    private XNode basedOn(XNode style) {
        XNode b = style.child("basedOn");
        return b == null ? null : styles.get(b.attr("val"));
    }

    // ================================================================== helpers

    private static String highlight(String name) {
        switch (name) {
            case "yellow": return "#ffff00";
            case "green": return "#00ff00";
            case "cyan": return "#00ffff";
            case "magenta": return "#ff00ff";
            case "blue": return "#0000ff";
            case "red": return "#ff0000";
            case "lightGray": return "#d3d3d3";
            case "darkGray": return "#a9a9a9";
            default: return null;
        }
    }

    private static int twipsToPx(int twips) { return Math.round(twips / 15f); }

    private static int parseInt(String v, int fallback) {
        if (v == null) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '\'': b.append("&#39;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
}
