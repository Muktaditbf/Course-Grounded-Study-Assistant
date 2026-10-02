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
import java.util.List;

/** Student home. SRS FR 1.3 role based dashboard, and the hub for FR 6 to FR 10. */
public class StudentDashboardActivity extends BaseActivity {

    private RecyclerView recycler;
    private TextView tvEmpty, tvName, tvPlan, tvBellCount;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student_dashboard);

        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvName = findViewById(R.id.tvName);
        tvPlan = findViewById(R.id.tvPlan);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        Anim.stagger(recycler);

        tvBellCount = findViewById(R.id.tvBellCount);
        findViewById(R.id.btnBell).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(NotificationsActivity.class); }
        });
        findViewById(R.id.btnJoin).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(JoinCourseActivity.class); }
        });
        tvPlan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(BillingActivity.class); }
        });

        setupBottomNav(R.id.nav_home);
        Anim.heroIn(findViewById(R.id.heroStudent));
    }

    @Override
    protected void onResume() {
        super.onResume();
        User u = currentUser();
        if (u == null) { logout(); return; }

        tvName.setText(u.name);
        tvPlan.setText("premium".equals(u.tier) ? getString(R.string.tier_premium)
                : "institutional".equals(u.tier) ? getString(R.string.tier_institutional)
                : getString(R.string.tier_free));

        refreshBell(u);
        loadCourses(u);
    }

    /**
     * The bell badge. Counting on resume means opening Notifications and coming back clears
     * it, which is what "seen" means here - there is no push channel to mark read against.
     */
    private void refreshBell(User u) {
        int unread = db.countNotificationsAfter(u.id, session.lastSeenNotification(u.id));
        if (unread <= 0) {
            tvBellCount.setVisibility(View.GONE);
            return;
        }
        tvBellCount.setText(unread > 9 ? "9+" : String.valueOf(unread));
        boolean wasHidden = tvBellCount.getVisibility() != View.VISIBLE;
        tvBellCount.setVisibility(View.VISIBLE);
        if (wasHidden) Anim.bounce(tvBellCount);
    }

    private void loadCourses(User u) {
        List<Course> courses = db.coursesForStudent(u.id);
        final List<Row> rows = new ArrayList<>();
        for (Course c : courses) {
            // Count only - loading every approved material's body just to size a list is
            // wasteful on the main thread and grows with the course.
            int approved = db.countApprovedMaterials(c.id);

            // A course with nothing approved yet must not wear the green "ready" chip.
            rows.add(new Row(c.id, c.code + "  " + c.title,
                    courseMeta(c),
                    getString(R.string.approved_count, approved),
                    approved > 0 ? 1 : 2)
                    .withIcon(CourseArt.iconFor(c)));
        }

        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        final RowAdapter adapter = new RowAdapter(rows, null);
        adapter.setOnRowClick(new RowAdapter.OnRowClick() {
            @Override public void onRow(Row row) {
                // The tapped card's title becomes the course header on the next screen.
                openShared(CourseHomeActivity.class, EXTRA_COURSE_ID, row.id,
                        adapter.tappedTitle());
            }
        });
        recycler.setAdapter(adapter);
        Anim.replay(recycler);
    }

    /** Faculty and schedule, skipping whichever the teacher left blank. */
    static String courseMeta(Course c) {
        String faculty = c.faculty == null ? "" : c.faculty.trim();
        String schedule = c.schedule == null ? "" : c.schedule.trim();
        if (faculty.isEmpty()) return schedule;
        if (schedule.isEmpty()) return faculty;
        return faculty + "\n" + schedule;
    }
}
