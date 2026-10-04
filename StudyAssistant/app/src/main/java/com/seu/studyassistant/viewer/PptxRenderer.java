package com.seu.studyassistant.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Draws PowerPoint (.pptx) slides as images, on the device, with no library.
 *
 * It follows the inheritance PowerPoint itself uses: a slide's placeholders take their
 * position and text style from the slide layout, which takes them from the slide master, and
 * colours go through the theme. Master and layout artwork (logos, bars, backgrounds) is drawn
 * under the slide's own shapes. Covered: backgrounds, preset and custom shapes, pictures,
 * groups, rotation, tables, connectors, and text with sizes, bold/italic/underline, colours,
 * alignment, bullets, numbering and indents.
 *
 * Not covered, and drawn as nothing rather than wrongly: charts, SmartArt, video, WordArt
 * effects, gradients beyond two stops, and EMF/WMF images.
 */
public final class PptxRenderer implements PageSource {

    private static final long EMU_PER_PT = 12700;

    private final OoxmlPackage pkg;
    private final List<String> slides = new ArrayList<>();
    private final long slideCx, slideCy;
    private final XNode defaultTextStyle;

    private final Map<String, Map<String, Integer>> themeCache = new HashMap<>();
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };

    public PptxRenderer(File file) throws IOException {
        pkg = new OoxmlPackage(file);
        XNode pres = pkg.xml("ppt/presentation.xml");
        if (pres == null) throw new IOException("not a presentation");

        XNode sz = pres.child("sldSz");
        slideCx = sz == null ? 9144000 : sz.attrLong("cx", 9144000);
        slideCy = sz == null ? 6858000 : sz.attrLong("cy", 6858000);
        defaultTextStyle = pres.child("defaultTextStyle");

        // Slide order is the order in presentation.xml, not the slide file numbers.
        XNode list = pres.child("sldIdLst");
        if (list != null) {
            for (XNode id : list.all("sldId")) {
                String target = pkg.relTarget("ppt/presentation.xml", id.attr("r:id"));
                if (target != null && pkg.has(target)) slides.add(target);
            }
        }
        if (slides.isEmpty()) {
            // Fallback for unusual files: numeric order of the slide parts.
            TreeMap<Integer, String> byNo = new TreeMap<>();
            for (int i = 1; i < 1000; i++) {
                String part = "ppt/slides/slide" + i + ".xml";
                if (pkg.has(part)) byNo.put(i, part);
            }
            slides.addAll(byNo.values());
        }
        if (slides.isEmpty()) throw new IOException("no slides");
    }

    @Override public int count() { return slides.size(); }

    @Override public float aspect(int index) { return (float) slideCy / slideCx; }

    @Override
    public void close() {
        images.evictAll();
        try { pkg.close(); } catch (IOException ignored) { }
    }

    // ===================================================================== render

    /** Everything one slide's drawing needs. */
    private final class Ctx {
        Canvas canvas;
        float scale;                // pixels per EMU
        String slide, layout, master;
        XNode slideRoot, layoutRoot, masterRoot;
        Map<String, Integer> theme;
        Map<String, String> clrMap;
        int slideNumber;
        final Map<String, Integer> autoNum = new HashMap<>();
    }

    /** Maps child EMU coordinates into slide EMU coordinates (group transforms). */
    private static final class Xf {
        final double ox, oy, sx, sy;
        Xf(double ox, double oy, double sx, double sy) { this.ox = ox; this.oy = oy; this.sx = sx; this.sy = sy; }
        static final Xf IDENTITY = new Xf(0, 0, 1, 1);
    }

    @Override
    public synchronized Bitmap render(int index, int widthPx) {
        try {
            int w = Math.max(1, widthPx);
            int h = Math.max(1, Math.round(w * aspect(index)));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);

            Ctx c = new Ctx();
            c.canvas = new Canvas(bmp);
            c.scale = (float) w / slideCx;
            c.slideNumber = index + 1;
            c.slide = slides.get(index);
            c.layout = pkg.relByType(c.slide, "/slideLayout");
            c.master = c.layout == null ? null : pkg.relByType(c.layout, "/slideMaster");
            c.slideRoot = pkg.xml(c.slide);
            c.layoutRoot = c.layout == null ? null : pkg.xml(c.layout);
            c.masterRoot = c.master == null ? null : pkg.xml(c.master);
            c.theme = theme(c.master == null ? null : pkg.relByType(c.master, "/theme"));
            c.clrMap = clrMap(c.masterRoot);
            if (c.slideRoot == null) return bmp;

            drawBackground(c);

            boolean slideShowsMaster = !"0".equals(c.slideRoot.attr("showMasterSp"));
            boolean layoutShowsMaster = c.layoutRoot == null
                    || !"0".equals(c.layoutRoot.attr("showMasterSp"));
            if (slideShowsMaster && layoutShowsMaster && c.masterRoot != null) {
                drawTree(c, spTree(c.masterRoot), c.master, true, Xf.IDENTITY);
            }
            if (slideShowsMaster && c.layoutRoot != null) {
                drawTree(c, spTree(c.layoutRoot), c.layout, true, Xf.IDENTITY);
            }
            drawTree(c, spTree(c.slideRoot), c.slide, false, Xf.IDENTITY);
            return bmp;
        } catch (OutOfMemoryError e) {
            images.evictAll();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static XNode spTree(XNode root) { return root == null ? null : root.path("cSld", "spTree"); }

    // ================================================================ background

    private void drawBackground(Ctx c) {
        String[] parts = {c.slide, c.layout, c.master};
        XNode[] roots = {c.slideRoot, c.layoutRoot, c.masterRoot};
        for (int i = 0; i < parts.length; i++) {
            if (roots[i] == null) continue;
            XNode bg = roots[i].path("cSld", "bg");
            if (bg == null) continue;

            XNode pr = bg.child("bgPr");
            if (pr != null) {
                if (fillRect(c, pr, parts[i], new RectF(0, 0, c.canvas.getWidth(), c.canvas.getHeight()), null)) return;
            }
            XNode ref = bg.child("bgRef");
            if (ref != null) {
                Integer col = color(c, ref, null);
                if (col != null) { c.canvas.drawColor(col); return; }
            }
        }
    }

    // ===================================================================== shapes

    private void drawTree(Ctx c, XNode tree, String part, boolean skipPlaceholders, Xf xf) {
        if (tree == null) return;
        for (XNode n : tree.children) {
            try {
                switch (n.name) {
                    case "sp":           drawShape(c, n, part, skipPlaceholders, xf); break;
                    case "pic":          drawPicture(c, n, part, skipPlaceholders, xf); break;
                    case "grpSp":        drawGroup(c, n, part, skipPlaceholders, xf); break;
                    case "graphicFrame": drawFrame(c, n, part, xf); break;
                    case "cxnSp":        drawConnector(c, n, part, xf); break;
                    case "AlternateContent": {
                        // Newer features ship with a Fallback drawing for older readers; use it.
                        XNode alt = n.child("Fallback");
                        if (alt == null) alt = n.child("Choice");
                        drawTree(c, alt, part, skipPlaceholders, xf);
                        break;
                    }
                    default: break;
                }
            } catch (Exception ignored) {
                // One unusual shape must not blank the rest of the slide.
            }
        }
    }

    private static XNode placeholder(XNode shape) {
        for (String nv : new String[]{"nvSpPr", "nvPicPr", "nvGraphicFramePr"}) {
            XNode ph = shape.path(nv, "nvPr", "ph");
            if (ph != null) return ph;
        }
        return null;
    }

    private static String phType(XNode ph) { return ph == null ? null : ph.attr("type", "obj"); }

    private static boolean sameType(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String t) {
        if (t == null) return "obj";
        switch (t) {
            case "ctrTitle": return "title";
            case "body": case "obj": case "subTitle": return "body";
            default: return t;
        }
    }

    /** The matching placeholder shape on the layout, then on the master. */
    private XNode[] inherited(Ctx c, XNode ph) {
        XNode onLayout = null, onMaster = null;
        if (ph != null) {
            String idx = ph.attr("idx");
            String type = phType(ph);
            XNode lt = spTree(c.layoutRoot);
            if (lt != null) {
                for (XNode s : lt.children) {
                    XNode p2 = placeholder(s);
                    if (p2 == null) continue;
                    if (idx != null && idx.equals(p2.attr("idx"))) { onLayout = s; break; }
                    if (onLayout == null && sameType(type, phType(p2))) onLayout = s;
                }
            }
            String lookFor = onLayout != null ? phType(placeholder(onLayout)) : type;
            XNode mt = spTree(c.masterRoot);
            if (mt != null) {
                for (XNode s : mt.children) {
                    XNode p2 = placeholder(s);
                    if (p2 != null && sameType(lookFor, phType(p2))) { onMaster = s; break; }
                }
            }
        }
        return new XNode[]{onLayout, onMaster};
    }

    /** Shape rectangle in pixels, inheriting the position from layout or master if needed. */
    private RectF rect(Ctx c, XNode xfrm, Xf xf) {
        if (xfrm == null) return null;
        XNode off = xfrm.child("off"), ext = xfrm.child("ext");
        if (off == null || ext == null) return null;
        double x = xf.ox + off.attrLong("x", 0) * xf.sx;
        double y = xf.oy + off.attrLong("y", 0) * xf.sy;
        double w = ext.attrLong("cx", 0) * xf.sx;
        double h = ext.attrLong("cy", 0) * xf.sy;
        return new RectF((float) (x * c.scale), (float) (y * c.scale),
                (float) ((x + w) * c.scale), (float) ((y + h) * c.scale));
    }

    private static XNode xfrmOf(XNode shape, XNode[] inh) {
        XNode x = shape.path("spPr", "xfrm");
        if (x == null && inh[0] != null) x = inh[0].path("spPr", "xfrm");
        if (x == null && inh[1] != null) x = inh[1].path("spPr", "xfrm");
        return x;
    }

    private void drawShape(Ctx c, XNode sp, String part, boolean skipPh, Xf xf) {
        XNode ph = placeholder(sp);
        if (ph != null && skipPh) return;   // layout/master placeholders are templates only

        XNode[] inh = inherited(c, ph);
        XNode xfrm = xfrmOf(sp, inh);
        RectF r = rect(c, xfrm, xf);
        if (r == null) return;

        int save = c.canvas.save();
        rotate(c, xfrm, r);

        XNode spPr = sp.child("spPr");
        XNode style = sp.child("style");
        Path path = geometry(spPr, r);
        boolean isTextBox = "1".equals(sp.path("nvSpPr", "cNvSpPr") == null ? null
                : sp.path("nvSpPr", "cNvSpPr").attr("txBox"));

        if (spPr != null) {
            Integer styleFill = isTextBox ? null : styleColor(c, style, "fillRef");
            fillPath(c, spPr, part, r, path, styleFill);
            strokePath(c, spPr, path, isTextBox ? null : styleColor(c, style, "lnRef"));
        }

        XNode tx = sp.child("txBody");
        if (tx != null) drawText(c, tx, r, ph, inh, styleFontColor(c, style));
        c.canvas.restoreToCount(save);
    }

    private void drawPicture(Ctx c, XNode pic, String part, boolean skipPh, Xf xf) {
        XNode ph = placeholder(pic);
        if (ph != null && skipPh) return;
        XNode[] inh = inherited(c, ph);
        XNode xfrm = xfrmOf(pic, inh);
        RectF r = rect(c, xfrm, xf);
        if (r == null) return;

        XNode blipFill = pic.child("blipFill");
        if (blipFill == null) return;
        int save = c.canvas.save();
        rotate(c, xfrm, r);
        drawBlip(c, blipFill, part, r, geometry(pic.child("spPr"), r));
        c.canvas.restoreToCount(save);
    }

    private void drawGroup(Ctx c, XNode grp, String part, boolean skipPh, Xf outer) {
        XNode x = grp.path("grpSpPr", "xfrm");
        Xf xf = outer;
        if (x != null) {
            XNode off = x.child("off"), ext = x.child("ext"), chOff = x.child("chOff"), chExt = x.child("chExt");
            if (off != null && ext != null && chOff != null && chExt != null) {
                double kx = chExt.attrLong("cx", 0) == 0 ? 1 : (double) ext.attrLong("cx", 1) / chExt.attrLong("cx", 1);
                double ky = chExt.attrLong("cy", 0) == 0 ? 1 : (double) ext.attrLong("cy", 1) / chExt.attrLong("cy", 1);
                xf = new Xf(outer.ox + outer.sx * (off.attrLong("x", 0) - chOff.attrLong("x", 0) * kx),
                        outer.oy + outer.sy * (off.attrLong("y", 0) - chOff.attrLong("y", 0) * ky),
                        outer.sx * kx, outer.sy * ky);
            }
        }
        drawTree(c, grp, part, skipPh, xf);
    }

    private void drawConnector(Ctx c, XNode cxn, String part, Xf xf) {
        XNode xfrm = cxn.path("spPr", "xfrm");
        RectF r = rect(c, xfrm, xf);
        if (r == null) return;
        boolean flipH = "1".equals(xfrm.attr("flipH")), flipV = "1".equals(xfrm.attr("flipV"));
        Path p = new Path();
        p.moveTo(flipH ? r.right : r.left, flipV ? r.bottom : r.top);
        p.lineTo(flipH ? r.left : r.right, flipV ? r.top : r.bottom);
        strokePath(c, cxn.child("spPr"), p, styleColor(c, cxn.child("style"), "lnRef"));
    }

    private void rotate(Ctx c, XNode xfrm, RectF r) {
        if (xfrm == null) return;
        long rot = xfrm.attrLong("rot", 0);
        if (rot != 0) c.canvas.rotate(rot / 60000f, r.centerX(), r.centerY());
    }

    // ================================================================== geometry

    private Path geometry(XNode spPr, RectF r) {
        Path p = new Path();
        XNode prst = spPr == null ? null : spPr.child("prstGeom");
        XNode cust = spPr == null ? null : spPr.child("custGeom");

        if (cust != null) {
            Path custom = customPath(cust, r);
            if (custom != null) return custom;
        }
        String kind = prst == null ? "rect" : prst.attr("prst", "rect");
        switch (kind) {
            case "ellipse":
                p.addOval(r, Path.Direction.CW);
                break;
            case "roundRect": {
                float rad = Math.min(r.width(), r.height()) * 0.1667f;
                p.addRoundRect(r, rad, rad, Path.Direction.CW);
                break;
            }
            case "triangle":
                p.moveTo(r.centerX(), r.top); p.lineTo(r.right, r.bottom); p.lineTo(r.left, r.bottom); p.close();
                break;
            case "rtTriangle":
                p.moveTo(r.left, r.top); p.lineTo(r.right, r.bottom); p.lineTo(r.left, r.bottom); p.close();
                break;
            case "diamond":
                p.moveTo(r.centerX(), r.top); p.lineTo(r.right, r.centerY());
                p.lineTo(r.centerX(), r.bottom); p.lineTo(r.left, r.centerY()); p.close();
                break;
            case "line": case "straightConnector1":
                p.moveTo(r.left, r.top); p.lineTo(r.right, r.bottom);
                break;
            case "rightArrow": {
                float head = Math.min(r.width() * 0.4f, r.height());
                float t = r.top + r.height() * 0.25f, b = r.bottom - r.height() * 0.25f;
                p.moveTo(r.left, t); p.lineTo(r.right - head, t); p.lineTo(r.right - head, r.top);
                p.lineTo(r.right, r.centerY()); p.lineTo(r.right - head, r.bottom);
                p.lineTo(r.right - head, b); p.lineTo(r.left, b); p.close();
                break;
            }
            case "chevron": case "homePlate": {
                float tip = Math.min(r.width() * 0.3f, r.height() * 0.5f);
                p.moveTo(r.left, r.top); p.lineTo(r.right - tip, r.top); p.lineTo(r.right, r.centerY());
                p.lineTo(r.right - tip, r.bottom); p.lineTo(r.left, r.bottom);
                if ("chevron".equals(kind)) p.lineTo(r.left + tip, r.centerY());
                p.close();
                break;
            }
            default:
                p.addRect(r, Path.Direction.CW);
        }
        return p;
    }

    /** custGeom: the shape's own path, in its own coordinate box scaled onto the rectangle. */
    private Path customPath(XNode cust, RectF r) {
        XNode lst = cust.child("pathLst");
        if (lst == null) return null;
        Path out = new Path();
        boolean any = false;
        for (XNode path : lst.all("path")) {
            long pw = path.attrLong("w", 0), phh = path.attrLong("h", 0);
            if (pw <= 0 || phh <= 0) return null;   // unscaled paths: fall back to the box
            float w = pw, h = phh;
            float sx = r.width() / w, sy = r.height() / h;
            for (XNode cmd : path.children) {
                List<XNode> pts = cmd.all("pt");
                float[] xy = new float[pts.size() * 2];
                for (int i = 0; i < pts.size(); i++) {
                    xy[2 * i] = r.left + pts.get(i).attrLong("x", 0) * sx;
                    xy[2 * i + 1] = r.top + pts.get(i).attrLong("y", 0) * sy;
                }
                switch (cmd.name) {
                    case "moveTo": if (xy.length >= 2) { out.moveTo(xy[0], xy[1]); any = true; } break;
                    case "lnTo": if (xy.length >= 2) out.lineTo(xy[0], xy[1]); break;
                    case "cubicBezTo": if (xy.length >= 6) out.cubicTo(xy[0], xy[1], xy[2], xy[3], xy[4], xy[5]); break;
                    case "quadBezTo": if (xy.length >= 4) out.quadTo(xy[0], xy[1], xy[2], xy[3]); break;
                    case "close": out.close(); break;
                    default: break;   // arcTo is rare in exported decks; skipped
                }
            }
        }
        return any ? out : null;
    }

    // ===================================================================== fills

    /** Fills a path from spPr. Returns false when spPr specifies no fill at all. */
    private boolean fillPath(Ctx c, XNode spPr, String part, RectF r, Path path, Integer styleFill) {
        if (spPr.child("noFill") != null) return true;
        if (fillRect(c, spPr, part, r, path)) return true;
        if (styleFill != null) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(styleFill);
            c.canvas.drawPath(path, p);
            return true;
        }
        return false;
    }

    /** Applies a solid, gradient or picture fill found directly in this node. */
    private boolean fillRect(Ctx c, XNode holder, String part, RectF r, Path path) {
        XNode solid = holder.child("solidFill");
        if (solid != null) {
            Integer col = color(c, solid, null);
            if (col == null) return false;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(col);
            if (path == null) c.canvas.drawRect(r, p); else c.canvas.drawPath(path, p);
            return true;
        }
        XNode grad = holder.child("gradFill");
        if (grad != null) {
            XNode gsLst = grad.child("gsLst");
            List<XNode> stops = gsLst == null ? new ArrayList<XNode>() : gsLst.all("gs");
            if (stops.isEmpty()) return false;
            Integer a = color(c, stops.get(0), null);
            Integer b = color(c, stops.get(stops.size() - 1), null);
            if (a == null || b == null) return false;
            XNode lin = grad.child("lin");
            double ang = lin == null ? 90 : lin.attrLong("ang", 5400000) / 60000.0;
            double rad = Math.toRadians(ang);
            float cx = r.centerX(), cy = r.centerY();
            float dx = (float) Math.cos(rad) * r.width() / 2, dy = (float) Math.sin(rad) * r.height() / 2;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, a, b, Shader.TileMode.CLAMP));
            if (path == null) c.canvas.drawRect(r, p); else c.canvas.drawPath(path, p);
            return true;
        }
        XNode blip = holder.child("blipFill");
        if (blip != null) {
            drawBlip(c, blip, part, r, path);
            return true;
        }
        return false;
    }

    private void strokePath(Ctx c, XNode spPr, Path path, Integer styleLine) {
        XNode ln = spPr == null ? null : spPr.child("ln");
        Integer col = null;
        float widthEmu = 12700;
        if (ln != null) {
            if (ln.child("noFill") != null) return;
            XNode solid = ln.child("solidFill");
            if (solid != null) col = color(c, solid, null);
            widthEmu = ln.attrLong("w", 12700);
        }
        if (col == null) col = styleLine;
        if (col == null) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(col);
        p.setStrokeWidth(Math.max(1f, widthEmu * c.scale));
        c.canvas.drawPath(path, p);
    }

    private Integer styleColor(Ctx c, XNode style, String ref) {
        if (style == null) return null;
        XNode r = style.child(ref);
        if (r == null || r.attrLong("idx", 0) == 0) return null;
        return color(c, r, null);
    }

    private Integer styleFontColor(Ctx c, XNode style) {
        if (style == null) return null;
        XNode r = style.child("fontRef");
        return r == null ? null : color(c, r, null);
    }

    // ==================================================================== pictures

    private void drawBlip(Ctx c, XNode blipFill, String part, RectF r, Path clip) {
        XNode blip = blipFill.child("blip");
        if (blip == null) return;
        String target = pkg.relTarget(part, blip.attr("r:embed") != null ? blip.attr("r:embed") : blip.attr("embed"));
        if (target == null) return;

        int tw = Math.max(1, Math.round(r.width())), th = Math.max(1, Math.round(r.height()));
        Bitmap bmp = image(target, tw, th);
        if (bmp == null) return;

        Rect src = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
        XNode crop = blipFill.child("srcRect");
        if (crop != null) {
            float l = crop.attrLong("l", 0) / 100000f, t = crop.attrLong("t", 0) / 100000f;
            float rr = crop.attrLong("r", 0) / 100000f, b = crop.attrLong("b", 0) / 100000f;
            src = new Rect(Math.round(bmp.getWidth() * l), Math.round(bmp.getHeight() * t),
                    Math.round(bmp.getWidth() * (1 - rr)), Math.round(bmp.getHeight() * (1 - b)));
            if (src.width() <= 0 || src.height() <= 0) src = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
        }
        int save = c.canvas.save();
        if (clip != null) c.canvas.clipPath(clip);
        c.canvas.drawBitmap(bmp, src, r, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        c.canvas.restoreToCount(save);
    }

    /** Decodes an embedded image no larger than needed, with a cache for repeated logos. */
    private Bitmap image(String part, int tw, int th) {
        String key = part + "@" + tw + "x" + th;
        Bitmap cached = images.get(key);
        if (cached != null) return cached;

        byte[] data = pkg.bytes(part);
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;   // EMF, WMF, SVG: not decodable here

        int sample = 1;
        while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (bmp != null) images.put(key, bmp);
        return bmp;
    }

    // ====================================================================== tables

    private void drawFrame(Ctx c, XNode frame, String part, Xf xf) {
        RectF r = rect(c, frame.child("xfrm"), xf);
        if (r == null) return;
        XNode tbl = frame.path("graphic", "graphicData", "tbl");
        if (tbl == null) return;   // charts and SmartArt are not drawn

        XNode grid = tbl.child("tblGrid");
        if (grid == null) return;
        List<Float> cols = new ArrayList<>();
        for (XNode gc : grid.all("gridCol")) cols.add(gc.attrLong("w", 0) * (float) xf.sx * c.scale);

        XNode tblPr = tbl.child("tblPr");
        boolean firstRow = tblPr != null && "1".equals(tblPr.attr("firstRow"));
        boolean banded = tblPr != null && "1".equals(tblPr.attr("bandRow"));
        int accent = c.theme.containsKey("accent1") ? c.theme.get("accent1") : 0xFF4472C4;

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, 12700 * c.scale));

        float y = r.top;
        int rowIndex = 0;
        for (XNode tr : tbl.all("tr")) {
            float rowH = tr.attrLong("h", 0) * (float) xf.sy * c.scale;
            float x = r.left;
            int col = 0;
            float tallest = rowH;
            for (XNode tc : tr.all("tc")) {
                if (col >= cols.size()) break;
                int span = (int) Math.max(1, tc.attrLong("gridSpan", 1));
                float w = 0;
                for (int k = 0; k < span && col + k < cols.size(); k++) w += cols.get(col + k);
                boolean merged = "1".equals(tc.attr("hMerge")) || "1".equals(tc.attr("vMerge"));
                RectF cell = new RectF(x, y, x + w, y + rowH);

                boolean header = firstRow && rowIndex == 0;
                Integer fill = null;
                XNode tcPr = tc.child("tcPr");
                if (tcPr != null && tcPr.child("solidFill") != null) fill = color(c, tcPr.child("solidFill"), null);
                else if (header) fill = accent;
                else if (banded) fill = blend(accent, Color.WHITE, (rowIndex % 2 == 1) ? 0.80f : 0.90f);

                if (!merged) {
                    if (fill != null) {
                        Paint fp = new Paint();
                        fp.setColor(fill);
                        c.canvas.drawRect(cell, fp);
                    }
                    border.setColor(fill != null ? Color.WHITE : 0xFFBFBFBF);
                    c.canvas.drawRect(cell, border);

                    XNode tx = tc.child("txBody");
                    if (tx != null) {
                        float used = drawText(c, tx, cell, null, new XNode[]{null, null},
                                header ? Integer.valueOf(Color.WHITE) : null);
                        tallest = Math.max(tallest, used);
                    }
                }
                x += w;
                col += span;
            }
            y += tallest;
            rowIndex++;
        }
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    // ======================================================================== text

    /**
     * Lays out and draws a text body inside its box. Returns the height the text needed, in
     * pixels including insets, which lets table rows grow to fit like PowerPoint's do.
     */
    private float drawText(Ctx c, XNode txBody, RectF box, XNode ph, XNode[] inh, Integer defaultColor) {
        XNode bodyPr = firstNonNull(txBody.child("bodyPr"),
                inh[0] == null ? null : inh[0].path("txBody", "bodyPr"),
                inh[1] == null ? null : inh[1].path("txBody", "bodyPr"));
        XNode own = txBody.child("bodyPr");

        float l = emuAttr(own, bodyPr, "lIns", 91440) * c.scale;
        float t = emuAttr(own, bodyPr, "tIns", 45720) * c.scale;
        float rr = emuAttr(own, bodyPr, "rIns", 91440) * c.scale;
        float b = emuAttr(own, bodyPr, "bIns", 45720) * c.scale;
        String anchor = attrChain("anchor", own, bodyPr);
        if (anchor == null) anchor = ph != null && "title".equals(norm(phType(ph))) ? "ctr" : "t";
        boolean noWrap = "none".equals(attrChain("wrap", own, bodyPr));

        float fontScale = 1f;
        XNode autofit = own == null ? null : own.child("normAutofit");
        if (autofit != null && autofit.attr("fontScale") != null) {
            fontScale = autofit.attrLong("fontScale", 100000) / 100000f;
        }

        List<XNode> levels = styleChain(c, txBody, ph, inh);
        int innerW = Math.max(1, Math.round(box.width() - l - rr));
        float innerH = box.height() - t - b;

        float spacing = lineSpacing(txBody, levels);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        StaticLayout layout = null;
        int layoutW = innerW;
        // Shrink to fit, the way PowerPoint's autofit keeps text inside its placeholder.
        for (float fit = 1f; fit >= 0.45f; fit -= 0.08f) {
            CharSequence text = buildText(c, txBody, levels, fontScale * fit, defaultColor);
            if (text.length() == 0) return box.height();
            // Unwrapped text is as wide as its longest line, and may run past its box.
            layoutW = noWrap ? Math.max(innerW, (int) Math.ceil(longestLine(text, paint))) : innerW;
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, layoutW)
                    .setIncludePad(false)
                    .setLineSpacing(0, spacing)
                    .build();
            if (layout.getHeight() <= innerH + 1 || noWrap) break;
        }
        if (layout == null) return box.height();

        float dy = 0;
        if ("ctr".equals(anchor)) dy = (innerH - layout.getHeight()) / 2f;
        else if ("b".equals(anchor)) dy = innerH - layout.getHeight();

        // Text wider than its box overflows evenly for centred text, leftwards for right-aligned.
        float dx = 0;
        if (layoutW > innerW) {
            Layout.Alignment a = layout.getParagraphAlignment(0);
            if (a == Layout.Alignment.ALIGN_CENTER) dx = (innerW - layoutW) / 2f;
            else if (a == Layout.Alignment.ALIGN_OPPOSITE) dx = innerW - layoutW;
        }

        int save = c.canvas.save();
        c.canvas.translate(box.left + l + dx, box.top + t + dy);
        layout.draw(c.canvas);
        c.canvas.restoreToCount(save);
        return layout.getHeight() + t + b;
    }

    private static float longestLine(CharSequence text, TextPaint paint) {
        float max = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                max = Math.max(max, Layout.getDesiredWidth(text, start, i, paint));
                start = i + 1;
            }
        }
        return max;
    }

    /** The first paragraph's percentage line spacing (lnSpc/spcPct), else single. */
    private static float lineSpacing(XNode txBody, List<XNode> chain) {
        XNode first = txBody.child("p");
        XNode pPr = first == null ? null : first.child("pPr");
        XNode pct = pPr == null ? null : pPr.path("lnSpc", "spcPct");
        if (pct == null) {
            XNode l = levelProps(chain, 0, "lnSpc");
            pct = l == null ? null : l.child("spcPct");
        }
        if (pct == null) return 1f;
        float v = pct.attrLong("val", 100000) / 100000f;
        return Math.max(0.6f, Math.min(2.5f, v));
    }

    /** lvl1pPr..lvl9pPr holders, most specific first. */
    private List<XNode> styleChain(Ctx c, XNode txBody, XNode ph, XNode[] inh) {
        List<XNode> chain = new ArrayList<>();
        add(chain, txBody.child("lstStyle"));
        if (inh[0] != null) add(chain, inh[0].path("txBody", "lstStyle"));
        if (inh[1] != null) add(chain, inh[1].path("txBody", "lstStyle"));
        if (ph != null && c.masterRoot != null) {
            XNode styles = c.masterRoot.child("txStyles");
            if (styles != null) {
                String type = norm(phType(ph));
                add(chain, styles.child("title".equals(type) ? "titleStyle"
                        : "body".equals(type) ? "bodyStyle" : "otherStyle"));
            }
        } else {
            add(chain, defaultTextStyle);
            if (c.masterRoot != null && c.masterRoot.child("txStyles") != null) {
                add(chain, c.masterRoot.child("txStyles").child("otherStyle"));
            }
        }
        return chain;
    }

    private static void add(List<XNode> l, XNode n) { if (n != null) l.add(n); }

    /** Paragraph property for a level, from the first style in the chain that sets it. */
    private static XNode levelProps(List<XNode> chain, int lvl, String childName) {
        for (XNode s : chain) {
            XNode p = s.child("lvl" + (lvl + 1) + "pPr");
            if (p == null) continue;
            XNode found = childName == null ? p : p.child(childName);
            if (found != null) return found;
        }
        return null;
    }

    private static String levelAttr(List<XNode> chain, int lvl, String attr) {
        for (XNode s : chain) {
            XNode p = s.child("lvl" + (lvl + 1) + "pPr");
            if (p != null && p.attr(attr) != null) return p.attr(attr);
        }
        return null;
    }

    private static String levelRunAttr(List<XNode> chain, int lvl, String attr) {
        for (XNode s : chain) {
            XNode p = s.path("lvl" + (lvl + 1) + "pPr", "defRPr");
            if (p != null && p.attr(attr) != null) return p.attr(attr);
        }
        return null;
    }

    private CharSequence buildText(Ctx c, XNode txBody, List<XNode> chain, float scale, Integer defaultColor) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        c.autoNum.clear();
        List<XNode> paras = txBody.all("p");

        for (int pi = 0; pi < paras.size(); pi++) {
            XNode p = paras.get(pi);
            XNode pPr = p.child("pPr");
            int lvl = pPr == null ? 0 : (int) pPr.attrLong("lvl", 0);
            int start = sb.length();

            boolean hasText = false;
            for (XNode r : p.children) {
                if (("r".equals(r.name) || "fld".equals(r.name)) && r.child("t") != null
                        && !r.child("t").text().isEmpty()) { hasText = true; break; }
            }

            // ---- bullet or number
            float firstSizePx = runSizePx(c, firstRunProps(p), chain, lvl, scale, pPr);
            if (hasText) {
                String bullet = bullet(c, pPr, chain, lvl);
                if (bullet != null) {
                    int bs = sb.length();
                    sb.append(bullet).append(' ');
                    sb.setSpan(new AbsoluteSizeSpan(Math.round(firstSizePx)), bs, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    Integer col = runColor(c, firstRunProps(p), chain, lvl, defaultColor);
                    sb.setSpan(new ForegroundColorSpan(col), bs, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            // ---- runs
            for (XNode r : p.children) {
                if ("br".equals(r.name)) { sb.append('\n'); continue; }
                if (!"r".equals(r.name) && !"fld".equals(r.name)) continue;
                XNode t = r.child("t");
                if (t == null) continue;
                String text = t.text();
                if ("fld".equals(r.name) && "slidenum".equals(r.attr("type"))) text = String.valueOf(c.slideNumber);
                if (text.isEmpty()) continue;

                XNode rPr = r.child("rPr");
                int rs = sb.length();
                sb.append(text);
                int re = sb.length();

                sb.setSpan(new AbsoluteSizeSpan(Math.round(runSizePx(c, rPr, chain, lvl, scale, pPr))), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(runColor(c, rPr, chain, lvl, defaultColor)), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                boolean bold = flag(rPr, "b", levelRunAttr(chain, lvl, "b"));
                boolean italic = flag(rPr, "i", levelRunAttr(chain, lvl, "i"));
                if (bold || italic) {
                    sb.setSpan(new StyleSpan(bold && italic ? Typeface.BOLD_ITALIC : bold ? Typeface.BOLD : Typeface.ITALIC),
                            rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                String u = rPr == null ? null : rPr.attr("u");
                if (u != null && !"none".equals(u)) sb.setSpan(new UnderlineSpan(), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                String strike = rPr == null ? null : rPr.attr("strike");
                if (strike != null && !"noStrike".equals(strike)) sb.setSpan(new StrikethroughSpan(), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            // An empty paragraph still takes a line at its own size, as in PowerPoint.
            if (sb.length() == start) {
                sb.append('​');
                XNode end = p.child("endParaRPr");
                sb.setSpan(new AbsoluteSizeSpan(Math.round(runSizePx(c, end, chain, lvl, scale, pPr))),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (pi < paras.size() - 1) sb.append('\n');
            int end = sb.length();

            // ---- paragraph alignment and indents
            String algn = pPr != null && pPr.attr("algn") != null ? pPr.attr("algn") : levelAttr(chain, lvl, "algn");
            Layout.Alignment a = "ctr".equals(algn) ? Layout.Alignment.ALIGN_CENTER
                    : "r".equals(algn) ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
            sb.setSpan(new AlignmentSpan.Standard(a), start, end, Spanned.SPAN_PARAGRAPH);

            String marL = pPr != null && pPr.attr("marL") != null ? pPr.attr("marL") : levelAttr(chain, lvl, "marL");
            String indent = pPr != null && pPr.attr("indent") != null ? pPr.attr("indent") : levelAttr(chain, lvl, "indent");
            float ml = parseLong(marL, 0) * c.scale, ind = parseLong(indent, 0) * c.scale;
            if (ml != 0 || ind != 0) {
                sb.setSpan(new LeadingMarginSpan.Standard(Math.max(0, Math.round(ml + ind)), Math.max(0, Math.round(ml))),
                        start, end, Spanned.SPAN_PARAGRAPH);
            }
        }
        return sb;
    }

    private static XNode firstRunProps(XNode p) {
        for (XNode r : p.children) if ("r".equals(r.name)) return r.child("rPr");
        return p.child("endParaRPr");
    }

    private String bullet(Ctx c, XNode pPr, List<XNode> chain, int lvl) {
        // The paragraph's own setting wins, then the style chain, nearest first.
        List<XNode> holders = new ArrayList<>();
        if (pPr != null) holders.add(pPr);
        for (XNode s : chain) {
            XNode p = s.child("lvl" + (lvl + 1) + "pPr");
            if (p != null) holders.add(p);
        }
        for (XNode h : holders) {
            if (h.child("buNone") != null) return null;
            XNode ch = h.child("buChar");
            if (ch != null) return ch.attr("char", "•");
            XNode num = h.child("buAutoNum");
            if (num != null) {
                String key = "n" + lvl;
                int start = (int) num.attrLong("startAt", 1);
                Integer cur = c.autoNum.get(key);
                int n = cur == null ? start : cur + 1;
                c.autoNum.put(key, n);
                String type = num.attr("type", "arabicPeriod");
                if (type.startsWith("alphaLc")) return (char) ('a' + (n - 1) % 26) + ".";
                if (type.startsWith("alphaUc")) return (char) ('A' + (n - 1) % 26) + ".";
                if (type.endsWith("ParenR")) return n + ")";
                return n + ".";
            }
        }
        return null;
    }

    private float runSizePx(Ctx c, XNode rPr, List<XNode> chain, int lvl, float scale, XNode pPr) {
        String sz = rPr == null ? null : rPr.attr("sz");
        if (sz == null && pPr != null && pPr.child("defRPr") != null) sz = pPr.child("defRPr").attr("sz");
        if (sz == null) sz = levelRunAttr(chain, lvl, "sz");
        long hundredths = parseLong(sz, 1800);
        return Math.max(1f, hundredths / 100f * EMU_PER_PT * c.scale * scale);
    }

    private int runColor(Ctx c, XNode rPr, List<XNode> chain, int lvl, Integer fallback) {
        if (rPr != null && rPr.child("solidFill") != null) {
            Integer col = color(c, rPr.child("solidFill"), null);
            if (col != null) return col;
        }
        if (fallback != null) return fallback;
        XNode def = levelProps(chain, lvl, "defRPr");
        if (def != null && def.child("solidFill") != null) {
            Integer col = color(c, def.child("solidFill"), null);
            if (col != null) return col;
        }
        Integer tx1 = scheme(c, "tx1");
        return tx1 == null ? Color.BLACK : tx1;
    }

    private static boolean flag(XNode rPr, String attr, String inherited) {
        String v = rPr == null ? null : rPr.attr(attr);
        if (v == null) v = inherited;
        return "1".equals(v) || "true".equals(v);
    }

    // ====================================================================== colour

    /** Reads the colour element inside a holder (solidFill, gs, fillRef...) with modifiers. */
    private Integer color(Ctx c, XNode holder, Integer phClr) {
        if (holder == null) return null;
        for (XNode n : holder.children) {
            Integer base = null;
            switch (n.name) {
                case "srgbClr": base = parseHex(n.attr("val")); break;
                case "schemeClr":
                    base = "phClr".equals(n.attr("val")) ? phClr : scheme(c, n.attr("val"));
                    break;
                case "sysClr": base = parseHex(n.attr("lastClr", "000000")); break;
                case "prstClr": base = preset(n.attr("val")); break;
                case "scrgbClr":
                    base = Color.rgb(pct(n.attr("r")), pct(n.attr("g")), pct(n.attr("b")));
                    break;
                default: continue;
            }
            if (base == null) return null;
            return modify(base, n);
        }
        return null;
    }

    private Integer scheme(Ctx c, String name) {
        if (name == null) return null;
        String mapped = c.clrMap.containsKey(name) ? c.clrMap.get(name) : name;
        Integer v = c.theme.get(mapped);
        if (v == null) {
            if (mapped.equals("dk1") || mapped.equals("tx1")) return Color.BLACK;
            if (mapped.equals("lt1") || mapped.equals("bg1")) return Color.WHITE;
        }
        return v;
    }

    private static int modify(int color, XNode clr) {
        float[] hsl = rgbToHsl(color);
        int alpha = 255;
        boolean hslChanged = false;
        int out = color;
        for (XNode m : clr.children) {
            float v = m.attrLong("val", 100000) / 100000f;
            switch (m.name) {
                case "lumMod": hsl[2] *= v; hslChanged = true; break;
                case "lumOff": hsl[2] += v; hslChanged = true; break;
                case "satMod": hsl[1] *= v; hslChanged = true; break;
                case "alpha": alpha = Math.round(255 * v); break;
                case "tint": {
                    if (hslChanged) { out = hslToRgb(hsl); hslChanged = false; }
                    out = blend(out, Color.WHITE, 1 - v);
                    hsl = rgbToHsl(out);
                    break;
                }
                case "shade": {
                    if (hslChanged) { out = hslToRgb(hsl); hslChanged = false; }
                    out = blend(out, Color.BLACK, 1 - v);
                    hsl = rgbToHsl(out);
                    break;
                }
                default: break;
            }
        }
        if (hslChanged) out = hslToRgb(hsl);
        return (out & 0x00FFFFFF) | (alpha << 24);
    }

    private static float[] rgbToHsl(int c) {
        float r = Color.red(c) / 255f, g = Color.green(c) / 255f, b = Color.blue(c) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float h = 0, s, l = (max + min) / 2;
        if (max == min) { s = 0; }
        else {
            float d = max - min;
            s = l > 0.5f ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new float[]{h, s, l};
    }

    private static int hslToRgb(float[] hsl) {
        float h = hsl[0], s = clamp(hsl[1]), l = clamp(hsl[2]);
        float r, g, b;
        if (s == 0) { r = g = b = l; }
        else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hue(p, q, h + 1f / 3); g = hue(p, q, h); b = hue(p, q, h - 1f / 3);
        }
        return Color.rgb(Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 1f / 2) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }

    private static Integer parseHex(String hex) {
        if (hex == null || hex.length() != 6) return null;
        try { return 0xFF000000 | Integer.parseInt(hex, 16); } catch (NumberFormatException e) { return null; }
    }

    private static int pct(String v) { return Math.round(parseLong(v, 0) / 100000f * 255); }

    private static Integer preset(String name) {
        if (name == null) return null;
        switch (name) {
            case "black": return Color.BLACK;
            case "white": return Color.WHITE;
            case "red": return Color.RED;
            case "green": return 0xFF008000;
            case "blue": return Color.BLUE;
            case "yellow": return Color.YELLOW;
            case "gray": case "grey": return Color.GRAY;
            default: return null;
        }
    }

    /** Theme colour scheme: dk1, lt1, dk2, lt2, accent1..6, hlink, folHlink. */
    private Map<String, Integer> theme(String part) {
        if (part == null) return new HashMap<>();
        Map<String, Integer> cached = themeCache.get(part);
        if (cached != null) return cached;
        Map<String, Integer> out = new HashMap<>();
        XNode root = pkg.xml(part);
        XNode scheme = root == null ? null : root.path("themeElements", "clrScheme");
        if (scheme != null) {
            for (XNode n : scheme.children) {
                XNode srgb = n.child("srgbClr");
                XNode sys = n.child("sysClr");
                Integer col = srgb != null ? parseHex(srgb.attr("val"))
                        : sys != null ? parseHex(sys.attr("lastClr", "000000")) : null;
                if (col != null) out.put(n.name, col);
            }
        }
        themeCache.put(part, out);
        return out;
    }

    private static Map<String, String> clrMap(XNode master) {
        Map<String, String> m = new HashMap<>();
        m.put("bg1", "lt1"); m.put("tx1", "dk1"); m.put("bg2", "lt2"); m.put("tx2", "dk2");
        XNode map = master == null ? null : master.child("clrMap");
        if (map != null) {
            for (String k : new String[]{"bg1", "tx1", "bg2", "tx2"}) {
                if (map.attr(k) != null) m.put(k, map.attr(k));
            }
        }
        return m;
    }

    // ===================================================================== helpers

    private static XNode firstNonNull(XNode... nodes) {
        for (XNode n : nodes) if (n != null) return n;
        return null;
    }

    private static String attrChain(String attr, XNode... nodes) {
        for (XNode n : nodes) if (n != null && n.attr(attr) != null) return n.attr(attr);
        return null;
    }

    private static float emuAttr(XNode own, XNode inherited, String attr, long fallback) {
        String v = attrChain(attr, own, inherited);
        return parseLong(v, fallback);
    }

    private static long parseLong(String v, long fallback) {
        if (v == null) return fallback;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return fallback; }
    }
}
