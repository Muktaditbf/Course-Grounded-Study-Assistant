package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;
import com.seu.studyassistant.adapter.RowAdapter;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Row;
import com.seu.studyassistant.model.User;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Teacher home. Entry point for UC3 (upload) and UC4 (approve and lock). */
public class TeacherDashboardActivity extends BaseActivity {

    private RecyclerView recycler;
    private TextView tvEmpty;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_dashboard);

        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        Anim.stagger(recycler);

        Anim.heroIn(findViewById(R.id.heroTeacher));

        findViewById(R.id.btnLogout).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { logout(); }
        });

        findViewById(R.id.btnCreate).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(CreateCourseActivity.class); }
        });
        findViewById(R.id.tabAnalytics).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(TeacherAnalyticsActivity.class); }
        });
        findViewById(R.id.tabNotifications).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(NotificationsActivity.class); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** True while refreshing for a background sync: no entry animation, no flicker. */
    private boolean quiet;

    @Override
    protected void onDataChanged() {
        quiet = true;
        refresh();
        quiet = false;
    }

    private void refresh() {
        User u = currentUser();
        if (u == null) { logout(); return; }
        ((TextView) findViewById(R.id.tvName)).setText(u.name);

        List<Course> courses = db.coursesForTeacher(u.id);
        final List<Row> rows = new ArrayList<>();
        final Set<Long> needsReview = new HashSet<>();

        for (Course c : courses) {
            // Counts come straight from SQL: loading every material body to compute two
            // integers ran on the main thread and grew with the size of the course.
            int[] counts = db.materialCounts(c.id);
            int approved = counts[0], pending = counts[1];
            if (pending > 0) needsReview.add(c.id);

            Row row = new Row(c.id, c.code + "  " + c.title,
                    getString(R.string.join_code) + ": " + c.joinCode
                            + "  •  " + getString(R.string.students_format, db.countStudents(c.id))
                            + "\n" + getString(R.string.approved_count, approved),
                    pending > 0 ? getString(R.string.pending_badge_format, pending) : null, 3);
            row.withIcon(CourseArt.iconFor(c));

            // The button follows the state: a course with pending material needs a decision,
            // not another upload.
            rows.add(row.withAction(pending > 0
                    ? getString(R.string.review)
                    : getString(R.string.upload_short)));
        }

        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setAdapter(new RowAdapter(rows,
                new RowAdapter.OnRowClick() {
                    @Override public void onRow(Row row) {
                        // Tapping the course opens the approve and lock screen (UC4).
                        open(ApproveMaterialsActivity.class, EXTRA_COURSE_ID, row.id);
                    }
                },
                new RowAdapter.OnActionClick() {
                    @Override public void onAction(Row row) {
                        open(needsReview.contains(row.id)
                                        ? ApproveMaterialsActivity.class
                                        : UploadMaterialActivity.class,
                                EXTRA_COURSE_ID, row.id);
                    }
                }));
        if (!quiet) Anim.replay(recycler);
    }
}
