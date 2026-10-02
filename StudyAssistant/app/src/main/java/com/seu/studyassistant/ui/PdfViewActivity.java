package com.seu.studyassistant.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.User;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads an uploaded PDF as its real pages.
 *
 * Uses the platform's PdfRenderer, so no library is added and the document never leaves the
 * device. Pages are rendered lazily as they scroll into view and cached by index: rendering
 * a whole lecture deck up front would both stall the screen and hold every page bitmap in
 * memory at once.
 *
 * Access is re-checked here as well as in MaterialViewActivity, because this screen shows the
 * document itself - it is the last place the Content Lock can still hold.
 */
public class PdfViewActivity extends BaseActivity {

    private RecyclerView recycler;
    private TextView tvEmpty;

    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private long materialId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_view);

        materialId = getIntent().getLongExtra(EXTRA_MATERIAL_ID, -1);
        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        User u = currentUser();
        if (u == null) { logout(); return; }

        Material m = db.materialById(materialId);
        if (m == null || !m.hasFile()) { finish(); return; }

        Course c = db.courseById(m.courseId);
        boolean owner = u.isTeacher() && c != null && c.teacherId == u.id;
        if (!m.approved && !owner) {
            toast(getString(R.string.err_material_locked));
            finish();
            return;
        }
        if (!owner && !u.isTeacher() && !db.isEnrolled(u.id, m.courseId)) {
            toast(getString(R.string.err_material_locked));
            finish();
            return;
        }

        setupHeader(m.title, true);
        open(m);
    }

    private void open(Material m) {
        File f = new File(m.filePath);
        if (!f.exists()) {
            showEmpty(getString(R.string.pdf_missing));
            return;
        }
        try {
            descriptor = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
        } catch (Exception e) {
            showEmpty(getString(R.string.pdf_unreadable));
            return;
        }

        int pages = renderer.getPageCount();
        if (pages == 0) {
            showEmpty(getString(R.string.pdf_unreadable));
            return;
        }

        tvEmpty.setVisibility(View.GONE);
        setHeaderAction(getString(R.string.pdf_page_count, pages), null);
        recycler.setAdapter(new PageAdapter(pages));
        Anim.stagger(recycler);
    }

    private void showEmpty(String message) {
        recycler.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(message);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) { }
        try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ pages

    private class PageAdapter extends RecyclerView.Adapter<PageHolder> {

        private final int count;
        /** Rendered pages, kept so scrolling back does not re-render. */
        private final android.util.LruCache<Integer, Bitmap> cache;

        PageAdapter(int count) {
            this.count = count;
            // A quarter of the heap is the usual safe budget for a bitmap cache.
            int kb = (int) (Runtime.getRuntime().maxMemory() / 1024);
            cache = new android.util.LruCache<Integer, Bitmap>(kb / 4) {
                @Override protected int sizeOf(Integer key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PageHolder(getLayoutInflater()
                    .inflate(R.layout.item_pdf_page, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final PageHolder h, int position) {
            final int index = position;
            h.label.setText(getString(R.string.pdf_page_of, index + 1, count));
            h.tag = index;

            Bitmap cached = cache.get(index);
            if (cached != null) {
                h.image.setImageBitmap(cached);
                h.progress.setVisibility(View.GONE);
                return;
            }

            h.image.setImageBitmap(null);
            h.progress.setVisibility(View.VISIBLE);

            final int targetWidth = Math.max(1, recycler.getWidth());
            io.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap bmp = render(index, targetWidth);
                    if (bmp == null) return;
                    cache.put(index, bmp);
                    main.post(new Runnable() {
                        @Override public void run() {
                            // The holder may have been recycled onto another page by now.
                            if (h.tag != index || isFinishing() || isDestroyed()) return;
                            h.image.setImageBitmap(bmp);
                            h.progress.setVisibility(View.GONE);
                        }
                    });
                }
            });
        }

        @Override
        public int getItemCount() { return count; }
    }

    /**
     * PdfRenderer allows only one open page at a time across the whole renderer, so every
     * render is serialised on the single IO thread and synchronised against close().
     */
    private Bitmap render(int index, int width) {
        synchronized (this) {
            if (renderer == null) return null;
            PdfRenderer.Page page = null;
            try {
                page = renderer.openPage(index);
                int w = Math.max(1, width);
                int h = Math.max(1, (int) ((long) w * page.getHeight() / page.getWidth()));

                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                // PdfRenderer draws only ink, so an unpainted page would come out transparent
                // and show black in dark mode. White first keeps the document readable.
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } catch (Exception e) {
                return null;
            } finally {
                if (page != null) {
                    try { page.close(); } catch (Exception ignored) { }
                }
            }
        }
    }

    static class PageHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label;
        final View progress;
        int tag = -1;

        PageHolder(View v) {
            super(v);
            image = v.findViewById(R.id.pageImage);
            label = v.findViewById(R.id.pageLabel);
            progress = v.findViewById(R.id.pageProgress);
        }
    }
}
