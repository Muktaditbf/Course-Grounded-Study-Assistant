package com.seu.studyassistant.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;
import com.seu.studyassistant.data.Callback;
import com.seu.studyassistant.data.CloudRepo;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.User;
import com.seu.studyassistant.viewer.DocxHtml;
import com.seu.studyassistant.viewer.PageSource;
import com.seu.studyassistant.viewer.PdfSource;
import com.seu.studyassistant.viewer.PptxRenderer;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows a teacher's upload as itself: PDF pages, PowerPoint slides, or a Word document.
 *
 * The original is downloaded from Firestore the first time it is opened and kept on the device,
 * so later opens are instant and work offline. Access is checked here as well as on the
 * material screen, because this is the screen that shows the document - the last place the
 * Content Lock can still hold. If the teacher revokes the material, the sync deletes the
 * downloaded copy too.
 */
public class DocumentViewActivity extends BaseActivity {

    private RecyclerView recycler;
    private WebView webView;
    private View loadingBlock, errorBlock;
    private ProgressBar progress;
    private TextView tvLoading, tvError;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private long materialId;
    private Material material;
    private PageSource pages;
    private File file;

    @Override
    protected boolean showsAiBubble() { return false; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_document_view);

        materialId = getIntent().getLongExtra(EXTRA_MATERIAL_ID, -1);
        recycler = findViewById(R.id.recycler);
        webView = findViewById(R.id.webView);
        loadingBlock = findViewById(R.id.loadingBlock);
        errorBlock = findViewById(R.id.errorBlock);
        progress = findViewById(R.id.progress);
        tvLoading = findViewById(R.id.tvLoading);
        tvError = findViewById(R.id.tvError);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setInitialPrefetchItemCount(2);
        recycler.setLayoutManager(lm);
        recycler.setItemViewCacheSize(4);

        findViewById(R.id.btnRetry).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });

        if (!checkAccess()) return;
        setupHeader(material.title, true);
        load();
    }

    private boolean checkAccess() {
        User u = currentUser();
        if (u == null) { logout(); return false; }
        material = db.materialById(materialId);
        if (material == null || !material.hasFile()) { finish(); return false; }

        Course c = db.courseById(material.courseId);
        boolean owner = u.isTeacher() && c != null && c.teacherId == u.id;
        boolean allowed = owner || (material.approved && db.isEnrolled(u.id, material.courseId));
        if (!allowed) {
            toast(getString(R.string.err_material_locked));
            finish();
            return false;
        }
        return true;
    }

    @Override
    protected void onDataChanged() {
        // Revoked or deleted while open: the sync has removed it, so close.
        if (db.materialById(materialId) == null) finish();
    }

    // ------------------------------------------------------------------- loading

    private void load() {
        showLoading(getString(R.string.preparing_file), -1, -1);
        material = db.materialById(materialId);
        if (material == null) { finish(); return; }

        if (material.hasLocalFile()) {
            open(new File(material.filePath));
            return;
        }

        String remoteId = db.materialRemoteId(materialId);
        if (remoteId == null || material.fileChunks <= 0) {
            showError(getString(R.string.file_download_failed));
            return;
        }
        CloudRepo.downloadFile(this, remoteId, material.fileChunks, material.fileVersion,
                material.fileName,
                new CloudRepo.Progress() {
                    @Override public void onProgress(int done, int total) {
                        if (!isFinishing()) showLoading(getString(R.string.downloading_file, done, total), done, total);
                    }
                },
                new Callback<File>() {
                    @Override public void onSuccess(File f) {
                        if (isFinishing() || isDestroyed()) return;
                        db.setLocalFilePath(materialId, f.getAbsolutePath());
                        open(f);
                    }
                    @Override public void onError(Exception e) {
                        if (isFinishing() || isDestroyed()) return;
                        showError(getString(e instanceof CloudRepo.FileNotReady
                                ? R.string.file_not_ready : R.string.file_download_failed));
                    }
                });
    }

    /** Parses the file off the main thread, then shows it the right way for its kind. */
    private void open(final File f) {
        file = f;
        setHeaderAction(getString(R.string.open_with), new View.OnClickListener() {
            @Override public void onClick(View v) { openWith(); }
        });

        final String kind = material.fileKind();
        showLoading(getString(R.string.preparing_file), -1, -1);
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    if ("docx".equals(kind)) {
                        final String html = DocxHtml.convert(f);
                        main.post(new Runnable() { @Override public void run() { showHtml(html); } });
                    } else {
                        final PageSource src = "pptx".equals(kind) ? new PptxRenderer(f) : new PdfSource(f);
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (isFinishing() || isDestroyed()) { src.close(); return; }
                                showPages(src, "pptx".equals(kind));
                            }
                        });
                    }
                } catch (Exception | OutOfMemoryError e) {
                    main.post(new Runnable() {
                        @Override public void run() { showError(getString(R.string.file_render_failed)); }
                    });
                }
            }
        });
    }

    private void showHtml(String html) {
        if (isFinishing() || isDestroyed()) return;
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(false);       // a document never needs scripts
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setBuiltInZoomControls(true);      // pinch to zoom
        ws.setDisplayZoomControls(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

        loadingBlock.setVisibility(View.GONE);
        errorBlock.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.setAlpha(0f);
        webView.animate().alpha(1f).setDuration(180).start();
    }

    private void showPages(PageSource src, boolean slides) {
        pages = src;
        if (src.count() == 0) { showError(getString(R.string.file_render_failed)); return; }
        recycler.setAdapter(new PageAdapter(src, slides));
        loadingBlock.setVisibility(View.GONE);
        errorBlock.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        recycler.setVisibility(View.VISIBLE);
        recycler.setAlpha(0f);
        recycler.animate().alpha(1f).setDuration(180).start();
    }

    private void showLoading(String text, int done, int total) {
        loadingBlock.setVisibility(View.VISIBLE);
        errorBlock.setVisibility(View.GONE);
        tvLoading.setText(text);
        if (total > 0) {
            progress.setIndeterminate(false);
            progress.setMax(total);
            progress.setProgress(done);
        } else {
            progress.setIndeterminate(true);
        }
    }

    private void showError(String text) {
        loadingBlock.setVisibility(View.GONE);
        recycler.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        errorBlock.setVisibility(View.VISIBLE);
        tvError.setText(text);
    }

    /** Lets the user open the original in PowerPoint, WPS, Google Slides or a PDF app. */
    private void openWith() {
        if (file == null || !file.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            String mime = material.fileMime;
            if (mime == null || mime.isEmpty()) {
                String k = material.fileKind();
                mime = "pdf".equals(k) ? "application/pdf"
                        : "pptx".equals(k) ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                        : "docx".equals(k) ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        : "*/*";
            }
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, getString(R.string.open_with)));
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            toast(getString(R.string.no_app_for_file));
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (pages != null) {
            final PageSource p = pages;
            pages = null;
            // Rendering may still hold the source; close it once that thread is done with it.
            new Thread(new Runnable() { @Override public void run() { p.close(); } }).start();
        }
        if (recycler != null) recycler.setAdapter(null);   // stops the page render thread
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    // --------------------------------------------------------------------- pages

    private class PageAdapter extends RecyclerView.Adapter<PageHolder> {

        private final PageSource src;
        private final boolean slides;
        private final LruCache<Integer, Bitmap> cache;
        private final ExecutorService render = Executors.newSingleThreadExecutor();

        PageAdapter(PageSource src, boolean slides) {
            this.src = src;
            this.slides = slides;
            int kb = (int) (Runtime.getRuntime().maxMemory() / 1024);
            cache = new LruCache<Integer, Bitmap>(kb / 5) {
                @Override protected int sizeOf(Integer key, Bitmap value) { return value.getByteCount() / 1024; }
            };
        }

        private int pageWidth() {
            int margin = getResources().getDimensionPixelSize(R.dimen.space_md);
            return Math.max(1, recycler.getWidth() - 2 * margin);
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PageHolder(getLayoutInflater().inflate(R.layout.item_pdf_page, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final PageHolder h, int position) {
            final int index = position;
            h.label.setText(getString(slides ? R.string.slide_of : R.string.pdf_page_of, index + 1, src.count()));
            h.tag = index;

            // Reserve the page's real height before it renders, so the list never jumps.
            final int width = pageWidth();
            ViewGroup.LayoutParams lp = h.image.getLayoutParams();
            lp.height = Math.round(width * src.aspect(index));
            h.image.setLayoutParams(lp);

            Bitmap cached = cache.get(index);
            if (cached != null) {
                h.image.setImageBitmap(cached);
                h.progress.setVisibility(View.GONE);
                return;
            }
            h.image.setImageBitmap(null);
            h.progress.setVisibility(View.VISIBLE);

            render.execute(new Runnable() {
                @Override public void run() {
                    if (pages == null || h.tag != index) return;   // scrolled past already
                    final Bitmap bmp = src.render(index, width);
                    if (bmp == null) return;
                    cache.put(index, bmp);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (h.tag != index || isFinishing() || isDestroyed()) return;
                            h.image.setImageBitmap(bmp);
                            h.progress.setVisibility(View.GONE);
                            h.image.setAlpha(0f);
                            h.image.animate().alpha(1f).setDuration(120).start();
                        }
                    });
                }
            });
        }

        @Override
        public int getItemCount() { return src.count(); }

        @Override
        public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
            render.shutdownNow();
            cache.evictAll();
        }
    }

    static class PageHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label;
        final View progress;
        volatile int tag = -1;

        PageHolder(View v) {
            super(v);
            image = v.findViewById(R.id.pageImage);
            label = v.findViewById(R.id.pageLabel);
            progress = v.findViewById(R.id.pageProgress);
        }
    }
}
