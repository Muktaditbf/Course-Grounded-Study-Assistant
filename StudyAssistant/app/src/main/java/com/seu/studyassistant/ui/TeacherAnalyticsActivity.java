package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.User;

import java.util.List;

/** FR 8.1 teacher analytics, and the Teacher Analytics Dashboard priced in Cost Report 8.3. */
public class TeacherAnalyticsActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_analytics);
        setupHeader(getString(R.string.analytics), true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        User u = currentUser();
        if (u == null || !u.isTeacher()) { finish(); return; }

        List<Course> courses = db.coursesForTeacher(u.id);
        int approved = 0, pending = 0, questions = 0;

        LinearLayout container = findViewById(R.id.coursesContainer);
        container.removeAllViews();

        for (Course c : courses) {
            List<Material> all = db.materials(c.id, false);
            int a = 0;
            for (Material m : all) if (m.approved) a++;
            int p = all.size() - a;
            int q = db.countQuestionsForCourse(c.id);

            approved += a;
            pending += p;
            questions += q;

            container.addView(card(c.code + "  " + c.title,
                    getString(R.string.students_format, db.countStudents(c.id))
                            + "  •  " + getString(R.string.materials_format, a, p)
                            + "  •  " + getString(R.string.questions_format, q), null));
        }

        ((TextView) findViewById(R.id.tvCourses)).setText(String.valueOf(courses.size()));
        ((TextView) findViewById(R.id.tvApproved)).setText(String.valueOf(approved));
        ((TextView) findViewById(R.id.tvPending)).setText(String.valueOf(pending));
        ((TextView) findViewById(R.id.tvQuestions)).setText(String.valueOf(questions));
    }

}
