package com.seu.studyassistant.viewer;

import android.graphics.Bitmap;

/** A document shown as a list of page images: PDF pages or PowerPoint slides. */
public interface PageSource {
    int count();

    /** Page height divided by page width. */
    float aspect(int index);

    /** Renders one page at this pixel width. Called off the main thread, one page at a time. */
    Bitmap render(int index, int widthPx);

    void close();
}
