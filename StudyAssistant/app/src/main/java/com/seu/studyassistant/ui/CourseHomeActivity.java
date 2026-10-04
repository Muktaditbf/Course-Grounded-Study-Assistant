package com.seu.studyassistant.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;
import com.seu.studyassistant.adapter.RowAdapter;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.Row;
import com.seu.studyassistant.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * One course as the student sees it. Only approved material is listed, which is the
 * Teacher-Enforced Content Lock made visible (SRS FR 3.2).
 */
public class CourseHomeActivity extends BaseActivity {

    private long courseId;
    private RecyclerView recycler;
    private TextView tvEmpty;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_course_home);

        courseId = getIntent().getLongExtra(EXTRA_COURSE_ID, -1);
        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        Anim.stagger(recycler);

        // The title is filled in here rather than in onResume because the shared element
        // has to be drawn with its text before the morph starts, or an empty view flies in.
        // Nothing sensitive is exposed: this is the same course name the card already showed.
        Course opening = db.courseById(courseId);
        if (opening != null) {
            ((TextView) findViewById(R.id.tvCourseTitle)).setText(opening.title);
        }
        receiveSharedTitle(R.id.tvCourseTitle);

        // The hero would fight the incoming morph, so it only animates on a plain entry.
        if (!enteredShared()) {
            Anim.heroIn(findViewById(R.id.heroCourse));
        }

        findViewById(R.id.btnAsk).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(CourseHomeActivity.this, AskQuestionActivity.class);
                i.putExtra(EXTRA_COURSE_ID, courseId);
                startActivity(i);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDataChanged() {
        refresh();
    }

    private void refresh() {
        Course c = db.courseById(courseId);
        if (c == null) { finish(); return; }

        setupHeader(c.code, true);
        ((TextView) findViewById(R.id.tvCourseTitle)).setText(c.title);
        // A course created without a faculty or schedule must not leave a stray blank line.
        TextView meta = findViewById(R.id.tvCourseMeta);
        String metaText = StudentDashboardActivity.courseMeta(c);
        meta.setText(metaText);
        meta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);

        User u = currentUser();
        // Only the teacher who OWNS the course previews unapproved drafts. Checking merely
        // "is a teacher" would let any teacher account read another course's locked material.
        boolean owner = u != null && u.isTeacher() && c.teacherId == u.id;

        List<Material> materials = db.materials(courseId, !owner);
        final List<Row> rows = new ArrayList<>();
        for (Material m : materials) {
            // To a student these are the materials they CAN read, so the chip says Approved.
            // "Locked" reads as "you cannot open this", which is the opposite of the truth.
            rows.add(new Row(m.id, m.title, getString(m.typeLabelRes()),
                    m.approved ? getString(R.string.approved)
                               : getString(R.string.pending_approval),
                    m.approved ? 1 : 3));
        }

        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setAdapter(new RowAdapter(rows, new RowAdapter.OnRowClick() {
            @Override public void onRow(Row row) {
                open(MaterialViewActivity.class, EXTRA_MATERIAL_ID, row.id);
            }
        }));
    }
}
