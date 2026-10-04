package com.seu.studyassistant.ui;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;
import com.seu.studyassistant.adapter.RowAdapter;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.Row;
import com.seu.studyassistant.model.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * UC4: Approve / Lock Course Material. This screen IS the Teacher-Enforced Content Lock.
 *
 * SRS NFR 12.2: only the teacher who owns the course may approve its materials, which is
 * enforced here before the list is shown.
 *
 * Toggling updates the single affected row in place rather than rebuilding the list, so the
 * teacher keeps their scroll position and sees the chip physically morph between states.
 */
public class ApproveMaterialsActivity extends BaseActivity {

    private long courseId;
    private RecyclerView recycler;
    private TextView tvEmpty;

    private final List<Row> rows = new ArrayList<>();
    private RowAdapter adapter;
    private Course course;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        courseId = getIntent().getLongExtra(EXTRA_COURSE_ID, -1);
        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvEmpty.setText(getString(R.string.no_materials));
        recycler.setLayoutManager(new LinearLayoutManager(this));
        Anim.stagger(recycler);

        TextView hint = findViewById(R.id.tvHint);
        hint.setVisibility(View.VISIBLE);
        hint.setText(getString(R.string.lock_hint));

        adapter = new RowAdapter(rows,
                new RowAdapter.OnRowClick() {
                    @Override public void onRow(Row row) {
                        open(MaterialViewActivity.class, EXTRA_MATERIAL_ID, row.id);
                    }
                },
                new RowAdapter.OnActionClick() {
                    @Override public void onAction(Row row) { onActionTapped(row); }
                });
        recycler.setAdapter(adapter);

        setupHeader(getString(R.string.approve_lock), true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        User u = currentUser();
        course = db.courseById(courseId);
        if (u == null || course == null) { finish(); return; }

        // NFR 12.2 ownership check.
        if (!u.isTeacher() || course.teacherId != u.id) {
            toast(getString(R.string.err_bad_credentials));
            finish();
            return;
        }

        setupHeader(course.code + " " + getString(R.string.approve_lock), true);
        setHeaderAction(getString(R.string.manage), new View.OnClickListener() {
            @Override public void onClick(View v) { showManageMenu(); }
        });
        load();
    }

    @Override
    protected void onDataChanged() {
        // Revoking and approving update rows in place, so only refresh when nothing is mid-toggle.
        if (course != null && db.courseById(courseId) == null) { finish(); return; }
        quiet = true;
        load();
        quiet = false;
    }

    /** True while refreshing for a background sync: no entry animation, no flicker. */
    private boolean quiet;

    /** Course-level actions: upload, edit details, delete. */
    private void showManageMenu() {
        String[] items = {getString(R.string.upload_material), getString(R.string.edit_course),
                getString(R.string.delete_course)};
        new AlertDialog.Builder(this)
                .setTitle(course.code)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) open(UploadMaterialActivity.class, EXTRA_COURSE_ID, courseId);
                        else if (which == 1) open(CreateCourseActivity.class, EXTRA_COURSE_ID, courseId);
                        else confirmDeleteCourse();
                    }
                })
                .show();
    }

    private void confirmDeleteCourse() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_course_title)
                .setMessage(getString(R.string.delete_course_body, course.code))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.act_delete, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        db.deleteCourse(courseId, new com.seu.studyassistant.data.Callback<Void>() {
                            @Override public void onSuccess(Void v) {
                                toast(getString(R.string.course_deleted));
                                finish();
                            }
                            @Override public void onError(Exception e) {
                                toast(getString(R.string.err_generic));
                            }
                        });
                    }
                })
                .show();
    }

    private void load() {
        List<Material> materials = db.materials(courseId, false);

        // Pending first: the queue exists to clear decisions, so what needs a decision is
        // what belongs at the top.
        Collections.sort(materials, new Comparator<Material>() {
            @Override public int compare(Material a, Material b) {
                if (a.approved != b.approved) return a.approved ? 1 : -1;
                return Long.compare(a.id, b.id);
            }
        });

        rows.clear();
        for (Material m : materials) rows.add(toRow(m));

        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        if (!quiet) Anim.replay(recycler);
    }

    private Row toRow(Material m) {
        Row row = new Row(m.id, m.title, getString(m.typeLabelRes()),
                m.approved ? getString(R.string.locked) : getString(R.string.pending_approval),
                // Amber for pending on both this screen and the dashboard, so the same state
                // never carries two different urgency signals.
                m.approved ? 1 : 3);
        return row.withAction(m.approved ? getString(R.string.revoke)
                                         : getString(R.string.approve));
    }

    // ------------------------------------------------------------------- toggling

    private void onActionTapped(final Row row) {
        final Material m = db.materialById(row.id);
        if (m == null) return;

        if (m.approved) {
            // Revoking pulls a lecture away from every enrolled student, so it asks first.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.revoke_confirm_title)
                    .setMessage(getString(R.string.revoke_confirm_body))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.revoke, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            apply(row, false);
                        }
                    })
                    .show();
        } else {
            apply(row, true);           // approving is safe, so it stays one tap
        }
    }

    private void apply(Row row, boolean approve) {
        db.setApproved(row.id, approve);

        if (approve) {
            db.notifyCourseStudents(courseId,
                    getString(R.string.approved_notice, course.code), row.title);
            toast(getString(R.string.material_approved_toast));
        } else {
            // Students were told when it appeared; they are told when it goes away.
            db.notifyCourseStudents(courseId,
                    getString(R.string.material_revoked_notice, course.code), row.title);
            toast(getString(R.string.material_revoked_toast));
        }

        int position = rows.indexOf(row);
        if (position < 0) { load(); return; }

        // Update the model, then animate the views that are actually on screen. Rebuilding
        // the list here would reset the scroll position and kill the morph.
        row.badge = approve ? getString(R.string.locked) : getString(R.string.pending_approval);
        row.badgeStyle = approve ? 1 : 3;
        row.action = approve ? getString(R.string.revoke) : getString(R.string.approve);

        RecyclerView.ViewHolder vh = recycler.findViewHolderForAdapterPosition(position);
        if (vh == null) {
            adapter.notifyItemChanged(position);
            return;
        }

        TextView badge = vh.itemView.findViewById(R.id.rowBadge);
        Button action = vh.itemView.findViewById(R.id.rowAction);
        action.setText(row.action);

        Anim.morphChip(badge,
                approve ? R.color.pending_bg : R.color.locked_green_bg,
                approve ? R.color.locked_green_bg : R.color.declined_amber_bg,
                approve ? R.color.locked_green_text : R.color.declined_amber_text,
                row.badge,
                approve ? R.drawable.bg_chip_green : R.drawable.bg_chip_amber);

        Anim.flashRow(vh.itemView,
                approve ? R.color.locked_green : R.color.declined_amber);
    }
}
