package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.User;

import java.util.List;

/**
 * UC8 / FR 8.1: the student progress dashboard.
 *
 * The documents require a progress dashboard but never define its metrics, so these
 * were chosen to reflect what the system actually measures: how many questions were
 * asked, how many the approved material could answer, and how many it declined.
 */
public class ProgressActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_progress);
        setupHeader(getString(R.string.progress_dashboard), true);
        setupBottomNav(R.id.nav_progress);
    }

    @Override
    protected void onResume() {
        super.onResume();
        User u = currentUser();
        if (u == null) { logout(); return; }

        int asked = db.countQuestions(u.id, null);
        int answered = db.countQuestions(u.id, Boolean.TRUE);
        int declined = db.countQuestions(u.id, Boolean.FALSE);
        int marks = db.countBookmarks(u.id);

        ((TextView) findViewById(R.id.tvAsked)).setText(String.valueOf(asked));
        ((TextView) findViewById(R.id.tvAnswered)).setText(String.valueOf(answered));
        ((TextView) findViewById(R.id.tvDeclined)).setText(String.valueOf(declined));
        ((TextView) findViewById(R.id.tvBookmarks)).setText(String.valueOf(marks));

        int rate = asked == 0 ? 0 : (int) Math.round(100.0 * answered / asked);
        ((ProgressBar) findViewById(R.id.progressBar)).setProgress(rate);
        ((TextView) findViewById(R.id.tvRate)).setText(rate + "%");

        LinearLayout container = findViewById(R.id.coursesContainer);
        container.removeAllViews();

        for (Course c : db.coursesForStudent(u.id)) {
            int approved = db.materials(c.id, true).size();
            int questions = db.countQuestionsForCourse(c.id);
            container.addView(card(c.code + "  " + c.title,
                    approved + " " + getString(R.string.approved_short)
                            + "  •  " + getString(R.string.questions_format, questions), null));
        }
    }
}
