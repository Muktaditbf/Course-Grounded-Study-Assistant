package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.data.Callback;
import com.seu.studyassistant.data.CloudRepo;
import com.seu.studyassistant.model.User;

/**
 * Enrollment by join code.
 *
 * The source documents assume enrollment exists (UC5 precondition: the student is
 * "enrolled in a course") but never specify how it happens. This screen closes that gap.
 */
public class JoinCourseActivity extends BaseActivity {

    private EditText etJoinCode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_join_course);
        setupHeader(getString(R.string.join_course), true);

        etJoinCode = findViewById(R.id.etJoinCode);

        clearErrorWhileTyping(R.id.tvError, etJoinCode);

        findViewById(R.id.btnJoin).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { attemptJoin(); }
        });
    }

    private void attemptJoin() {
        User u = currentUser();
        if (u == null) { logout(); return; }

        String code = etJoinCode.getText().toString().trim();
        if (code.isEmpty()) {
            showError(R.id.tvError, getString(R.string.err_fill_all));
            return;
        }

        final View btn = findViewById(R.id.btnJoin);
        btn.setEnabled(false);
        showError(R.id.tvError, null);
        CloudRepo.joinByCode(this, db.uidOf(u.id), code, new Callback<String>() {
            @Override public void onSuccess(String courseCode) {
                toast(getString(R.string.joined_toast, courseCode));
                finish();
            }
            @Override public void onError(Exception e) {
                btn.setEnabled(true);
                showError(R.id.tvError, getString(e instanceof CloudRepo.CodeNotFound
                        ? R.string.invalid_code : R.string.err_generic));
            }
        });
    }
}
