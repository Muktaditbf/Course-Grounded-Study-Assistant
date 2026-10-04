package com.seu.studyassistant.viewer;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * PDF pages through the platform's PdfRenderer: exact output, no library. PdfRenderer allows
 * one open page at a time, so every call is synchronised.
 */
public final class PdfSource implements PageSource {

    private final ParcelFileDescriptor fd;
    private final PdfRenderer renderer;
    private final float[] aspects;

    public PdfSource(File file) throws IOException {
        fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(fd);
        aspects = new float[renderer.getPageCount()];
        for (int i = 0; i < aspects.length; i++) {
            PdfRenderer.Page p = renderer.openPage(i);
            aspects[i] = (float) p.getHeight() / Math.max(1, p.getWidth());
            p.close();
        }
    }

    @Override public int count() { return aspects.length; }

    @Override public float aspect(int index) { return aspects[index]; }

    @Override
    public synchronized Bitmap render(int index, int widthPx) {
        PdfRenderer.Page page = null;
        try {
            page = renderer.openPage(index);
            int w = Math.max(1, widthPx);
            int h = Math.max(1, Math.round(w * aspects[index]));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            // PdfRenderer draws only ink; without a white page it shows black in dark mode.
            bmp.eraseColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (page != null) try { page.close(); } catch (Exception ignored) { }
        }
    }

    @Override
    public synchronized void close() {
        try { renderer.close(); } catch (Exception ignored) { }
        try { fd.close(); } catch (Exception ignored) { }
    }
}
