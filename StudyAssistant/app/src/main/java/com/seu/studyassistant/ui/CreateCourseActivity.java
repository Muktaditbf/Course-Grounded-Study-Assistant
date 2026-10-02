package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.data.DatabaseHelper;
import com.seu.studyassistant.model.User;

/** FR 2.1: a teacher creates and manages a course. */
public class CreateCourseActivity extends BaseActivity {

    private EditText etCode, etTitle, etFaculty, etSchedule, etJoinCode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_course);
        setupHeader(getString(R.string.create_course), true);

        etCode = findViewById(R.id.etCode);
        etTitle = findViewById(R.id.etTitle);
        etFaculty = findViewById(R.id.etFaculty);
        etSchedule = findViewById(R.id.etSchedule);
        etJoinCode = findViewById(R.id.etJoinCode);

        User u = currentUser();
        if (u != null) etFaculty.setText(u.name);

        clearErrorWhileTyping(R.id.tvError, etCode, etTitle, etJoinCode);

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
    }

    private void save() {
        User u = currentUser();
        if (u == null || !u.isTeacher()) { finish(); return; }

        String code = etCode.getText().toString().trim();
        String title = etTitle.getText().toString().trim();
        String faculty = etFaculty.getText().toString().trim();
        String schedule = etSchedule.getText().toString().trim();
        // Normalised the same way the join path normalises it, so the two can never disagree.
        String joinCode = DatabaseHelper.normaliseJoinCode(etJoinCode.getText().toString());

        if (code.isEmpty() || title.isEmpty() || joinCode.length() < 4) {
            showError(R.id.tvError, getString(R.string.err_fill_all));
            return;
        }

        // Say what is actually wrong. Reporting a taken join code as "fill in all fields"
        // sends the teacher hunting through fields that are all correctly filled.
        if (db.joinCodeExists(joinCode)) {
            showError(R.id.tvError, getString(R.string.err_join_code_taken, joinCode));
            return;
        }

        long id = db.createCourse(code, title, faculty, schedule, joinCode, u.id);
        if (id <= 0) {
            showError(R.id.tvError, getString(R.string.err_join_code_taken, joinCode));
            return;
        }

        showError(R.id.tvError, null);
        toast(getString(R.string.course_created));
        finish();
    }
}
