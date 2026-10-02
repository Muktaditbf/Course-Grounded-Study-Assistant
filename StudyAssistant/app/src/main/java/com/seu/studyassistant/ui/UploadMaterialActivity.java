package com.seu.studyassistant.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.seu.studyassistant.R;
import com.seu.studyassistant.data.DocumentImporter;
import com.seu.studyassistant.data.FileStore;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.User;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UC3: Upload Course Material, and FR 2.2 (every upload is chunked for retrieval).
 *
 * The teacher can either pick a real file from the device - a PDF slide deck, lab manual,
 * or a text/markdown note - or type the content directly. Picked files are read on device
 * through the Storage Access Framework; nothing is uploaded anywhere.
 */
public class UploadMaterialActivity extends BaseActivity {

    private static final String[] TYPE_KEYS = {"lecture", "lab", "assignment", "quiz", "notes"};

    /** MIME types the Storage Access Framework should offer. */
    private static final String[] ACCEPTED_MIME = {
            "application/pdf", "text/plain", "text/markdown", "text/csv",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"};

    private long courseId;
    private EditText etTitle, etBody;
    private Spinner spType;
    private CheckBox cbApprove;
    private MaterialButton btnPickFile;
    private TextView tvFileStatus;
    private ProgressBar fileProgress;

    private ActivityResultLauncher<Intent> filePicker;
    private ExecutorService io;
    private Handler main;

    /**
     * The title the last import filled in, so picking a different file can refresh it.
     *
     * Without this the field only ever filled when empty, and swapping the file left the
     * previous file's name behind. Comparing against it is what separates "still the name we
     * suggested" from "the teacher typed their own title", which must never be overwritten.
     */
    private String autoFilledTitle;

    /** The original upload, copied into app storage so students can read it. */
    private String pickedFilePath, pickedFileName, pickedFileMime;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upload_material);

        courseId = getIntent().getLongExtra(EXTRA_COURSE_ID, -1);
        etTitle = findViewById(R.id.etTitle);
        etBody = findViewById(R.id.etBody);
        spType = findViewById(R.id.spType);
        cbApprove = findViewById(R.id.cbApprove);
        btnPickFile = findViewById(R.id.btnPickFile);
        tvFileStatus = findViewById(R.id.tvFileStatus);
        fileProgress = findViewById(R.id.fileProgress);

        io = Executors.newSingleThreadExecutor();
        main = new Handler(Looper.getMainLooper());

        Course c = db.courseById(courseId);
        setupHeader((c == null ? "" : c.code + " ") + getString(R.string.upload_material), true);

        String[] labels = {
                getString(R.string.type_lecture), getString(R.string.type_lab),
                getString(R.string.type_assignment), getString(R.string.type_quiz),
                getString(R.string.type_notes)};
        spType.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));

        filePicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null
                            && result.getData().getData() != null) {
                        importFile(result.getData().getData());
                    }
                });

        btnPickFile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchPicker(); }
        });

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
    }

    @Override
    protected void onDestroy() {
        if (io != null) io.shutdownNow();
        super.onDestroy();
    }

    // --------------------------------------------------------------- file import

    private void launchPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, ACCEPTED_MIME);
        try {
            filePicker.launch(i);
        } catch (Exception e) {
            // No document provider on the device: typing the content still works.
            showError(R.id.tvError, getString(R.string.err_file_unreadable));
        }
    }

    /** Reads the document off the main thread, then fills the form with what it found. */
    private void importFile(final Uri uri) {
        showError(R.id.tvError, null);
        setBusy(true);

        final String unsupported = getString(R.string.err_unsupported_file);
        final String empty = getString(R.string.err_pdf_no_text);
        final String unreadable = getString(R.string.err_file_unreadable);
        final String legacy = getString(R.string.err_legacy_office);
        final String fileName = DocumentImporter.displayName(this, uri);

        io.execute(new Runnable() {
            @Override public void run() {
                final DocumentImporter.Result r = DocumentImporter.read(
                        UploadMaterialActivity.this, uri, unsupported, empty, unreadable, legacy);

                // Keep the original beside the extracted text. The text is what retrieval
                // indexes; the file is what the student actually reads.
                final String storedPath = r.ok
                        ? FileStore.save(UploadMaterialActivity.this, uri, fileName) : null;
                final String mime = getContentResolver().getType(uri);

                main.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        setBusy(false);
                        if (!r.ok) {
                            tvFileStatus.setText(getString(R.string.file_or_paste));
                            showError(R.id.tvError, r.error);
                            return;
                        }
                        pickedFilePath = storedPath;
                        pickedFileName = fileName;
                        pickedFileMime = mime;
                        applyImport(fileName, r);
                    }
                });
            }
        });
    }

    private void applyImport(String fileName, DocumentImporter.Result r) {
        etBody.setText(r.text);

        // Refresh the title when it is still ours to set - empty, or untouched since the last
        // import. A title the teacher typed themselves is left exactly as they wrote it.
        String current = etTitle.getText().toString().trim();
        if (current.isEmpty() || current.equals(autoFilledTitle)) {
            etTitle.setText(r.suggestedTitle);
            autoFilledTitle = r.suggestedTitle;
        }
        tvFileStatus.setText(getString(R.string.file_loaded, fileName)
                + "  •  " + getString(R.string.chars_extracted, r.text.length()));
        btnPickFile.setText(getString(R.string.replace_file));
        Anim.pulse(tvFileStatus);
    }

    private void setBusy(boolean busy) {
        fileProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnPickFile.setEnabled(!busy);
        if (busy) tvFileStatus.setText(getString(R.string.reading_file));
    }

    // --------------------------------------------------------------------- save

    private void save() {
        User u = currentUser();
        Course c = db.courseById(courseId);
        if (u == null || c == null) { finish(); return; }

        String title = etTitle.getText().toString().trim();
        String body = etBody.getText().toString().trim();

        // Short text cannot be retrieved from meaningfully, so require a real passage.
        if (title.isEmpty() || body.length() < DocumentImporter.MIN_USEFUL_CHARS) {
            showError(R.id.tvError, getString(R.string.err_fill_all));
            return;
        }

        String type = TYPE_KEYS[spType.getSelectedItemPosition()];
        boolean approve = cbApprove.isChecked();
        db.addMaterial(courseId, title, type, body, approve,
                pickedFileName, pickedFilePath, pickedFileMime);

        if (approve) {
            db.notifyCourseStudents(courseId, getString(R.string.new_material_notice, c.code), title);
        }

        showError(R.id.tvError, null);
        toast(getString(R.string.material_uploaded));
        finish();
    }
}
