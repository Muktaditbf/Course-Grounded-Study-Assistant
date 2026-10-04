package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.User;

/**
 * Reads one material. UC7: bookmark a topic for later revision.
 *
 * This is the only screen that renders a material's full body, which makes it the last gate of
 * the Teacher-Enforced Content Lock. It re-checks access on every resume rather than trusting
 * the id it was handed, so a material revoked while this screen sits in the back stack closes
 * itself as soon as the student returns to it.
 */
public class MaterialViewActivity extends BaseActivity {

    private long materialId;
    /** Opens the original once, on first entry, so a student lands on the real document. */
    private boolean autoOpen;
    private Button btnBookmark;
    private View btnOpenFile, tvTextLabel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_material_view);

        materialId = getIntent().getLongExtra(EXTRA_MATERIAL_ID, -1);
        autoOpen = savedInstanceState == null;
        btnBookmark = findViewById(R.id.btnBookmark);
        btnOpenFile = findViewById(R.id.btnOpenFile);
        tvTextLabel = findViewById(R.id.tvTextLabel);

        btnBookmark.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        User u = currentUser();
        if (u == null) { logout(); return; }

        Material m = db.materialById(materialId);
        if (m == null) { finish(); return; }

        Course c = db.courseById(m.courseId);
        boolean owner = u.isTeacher() && c != null && c.teacherId == u.id;

        // The Content Lock: unapproved material is readable only by the teacher who owns it.
        if (!m.approved && !owner) {
            toast(getString(R.string.err_material_locked));
            finish();
            return;
        }

        // A student must also be enrolled in the course the material belongs to.
        if (!owner && !u.isTeacher() && !db.isEnrolled(u.id, m.courseId)) {
            toast(getString(R.string.err_material_locked));
            finish();
            return;
        }

        render(m);

        if (owner) {
            setHeaderAction(getString(R.string.manage), new View.OnClickListener() {
                @Override public void onClick(View v) { showManageMenu(); }
            });
        }
    }

    @Override
    protected void onDataChanged() {
        // Re-run the access checks and re-render: a revoked or deleted material closes itself.
        onResume();
    }

    private void showManageMenu() {
        String[] items = {getString(R.string.edit_material), getString(R.string.delete_material)};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        Material m = db.materialById(materialId);
                        if (m == null) return;
                        if (which == 0) {
                            android.content.Intent i = new android.content.Intent(
                                    MaterialViewActivity.this, UploadMaterialActivity.class);
                            i.putExtra(EXTRA_MATERIAL_ID, materialId);
                            startActivity(i);
                        } else {
                            confirmDelete(m);
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final Material m) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.delete_material_title)
                .setMessage(getString(R.string.delete_material_body, m.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.act_delete, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        db.deleteMaterial(m.id);
                        toast(getString(R.string.material_deleted));
                        finish();
                    }
                })
                .show();
    }

    private void render(Material m) {
        setupHeader(getString(m.typeLabelRes()), true);
        ((TextView) findViewById(R.id.tvTitle)).setText(m.title);
        ((TextView) findViewById(R.id.tvType)).setText(getString(m.typeLabelRes()));
        ((TextView) findViewById(R.id.tvBody)).setText(m.body);

        TextView approved = findViewById(R.id.tvApproved);
        if (m.approved) {
            approved.setText(getString(R.string.approved));
            approved.setBackgroundResource(R.drawable.bg_chip_green);
            approved.setTextColor(getResources().getColor(R.color.locked_green_text));
        } else {
            // Only the owning teacher previewing their own draft reaches this branch.
            approved.setText(getString(R.string.pending_approval));
            approved.setBackgroundResource(R.drawable.bg_chip_amber);
            approved.setTextColor(getResources().getColor(R.color.declined_amber_text));
        }

        // An uploaded document is read as itself - its real pages, slides or layout. The
        // extracted text is only the AI's search index, so it is not shown in its place.
        TextView body = findViewById(R.id.tvBody);
        tvTextLabel.setVisibility(View.GONE);
        if (m.hasViewableOriginal()) {
            String kind = m.fileKind();
            ((Button) btnOpenFile).setText(getString("pptx".equals(kind) ? R.string.open_slides
                    : "docx".equals(kind) ? R.string.open_document : R.string.open_pdf));
            btnOpenFile.setVisibility(View.VISIBLE);
            body.setVisibility(View.GONE);
            btnOpenFile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    open(DocumentViewActivity.class, EXTRA_MATERIAL_ID, materialId);
                }
            });
            if (autoOpen) {
                autoOpen = false;
                open(DocumentViewActivity.class, EXTRA_MATERIAL_ID, materialId);
            }
        } else {
            btnOpenFile.setVisibility(View.GONE);
            body.setVisibility(View.VISIBLE);
        }

        refreshBookmark();
    }

    private void toggle() {
        User u = currentUser();
        if (u == null) { logout(); return; }
        db.toggleBookmark(u.id, materialId);
        refreshBookmark();
        Anim.bounce(btnBookmark);
    }

    private void refreshBookmark() {
        User u = currentUser();
        if (u == null) return;
        boolean saved = db.isBookmarked(u.id, materialId);
        btnBookmark.setText(saved ? getString(R.string.bookmarked) : getString(R.string.bookmark));
    }
}
