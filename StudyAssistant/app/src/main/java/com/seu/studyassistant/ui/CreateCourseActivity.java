package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.data.Callback;
import com.seu.studyassistant.data.CloudRepo;
import com.seu.studyassistant.data.DatabaseHelper;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.User;

/** FR 2.1: a teacher creates and manages a course. */
public class CreateCourseActivity extends BaseActivity {

    private EditText etCode, etTitle, etFaculty, etSchedule, etJoinCode;

    /** Set when editing an existing course rather than creating one. */
    private Course editing;

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

        long editId = getIntent().getLongExtra(EXTRA_COURSE_ID, -1);
        if (editId > 0) {
            editing = db.courseById(editId);
            if (editing == null || u == null || editing.teacherId != u.id) { finish(); return; }
            setupHeader(getString(R.string.edit_course), true);
            etCode.setText(editing.code);
            etTitle.setText(editing.title);
            etFaculty.setText(editing.faculty);
            etSchedule.setText(editing.schedule);
            // Students joined with this code, so it cannot be changed after creation.
            etJoinCode.setText(editing.joinCode);
            etJoinCode.setEnabled(false);
            ((android.widget.Button) findViewById(R.id.btnSave)).setText(R.string.save_changes);
        }

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

        if (editing != null) {
            if (code.isEmpty() || title.isEmpty()) {
                showError(R.id.tvError, getString(R.string.err_fill_all));
                return;
            }
            db.updateCourse(editing.id, code, title, faculty, schedule);
            toast(getString(R.string.course_updated));
            finish();
            return;
        }

        // The join code becomes a Firestore document id, so it cannot contain a slash.
        if (code.isEmpty() || title.isEmpty() || joinCode.length() < 4 || joinCode.contains("/")) {
            showError(R.id.tvError, getString(R.string.err_fill_all));
            return;
        }

        final View btn = findViewById(R.id.btnSave);
        btn.setEnabled(false);
        showError(R.id.tvError, null);
        final String taken = joinCode;
        CloudRepo.createCourse(this, db.uidOf(u.id), code, title, faculty, schedule, joinCode,
                new Callback<String>() {
            @Override public void onSuccess(String courseId) {
                toast(getString(R.string.course_created));
                finish();
            }
            @Override public void onError(Exception e) {
                btn.setEnabled(true);
                // Say what is actually wrong. The rules reject a join code that is already
                // used, which arrives as permission denied; anything else is a network problem.
                showError(R.id.tvError, CloudRepo.isPermissionDenied(e)
                        ? getString(R.string.err_join_code_taken, taken)
                        : getString(R.string.err_generic));
            }
        });
    }
}
