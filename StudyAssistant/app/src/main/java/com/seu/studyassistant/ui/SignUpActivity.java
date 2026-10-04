package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.data.Callback;
import com.seu.studyassistant.data.CloudRepo;
import com.seu.studyassistant.model.User;

/** UC1: Sign Up. Main success scenario plus alternative course 5.a (validation failure). */
public class SignUpActivity extends BaseActivity {

    private EditText etName, etEmail, etContact, etPassword;
    private RadioButton rbTeacher;

    @Override
    protected boolean showsAiBubble() { return false; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);
        setupHeader(getString(R.string.sign_up), true);

        etName = findViewById(R.id.etName);
        etEmail = findViewById(R.id.etEmail);
        etContact = findViewById(R.id.etContact);
        etPassword = findViewById(R.id.etPassword);
        rbTeacher = findViewById(R.id.rbTeacher);

        findViewById(R.id.btnSignUp).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { attemptSignUp(); }
        });

        findViewById(R.id.tvGoLogin).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    private void attemptSignUp() {
        String name = etName.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String contact = etContact.getText().toString().trim();
        String password = etPassword.getText().toString();

        boolean valid = !name.isEmpty()
                && Patterns.EMAIL_ADDRESS.matcher(email).matches()
                && contact.length() >= 6
                && password.length() >= 8;

        if (!valid) {
            showError(R.id.tvError, getString(R.string.err_fill_all));
            return;
        }
        if (!CloudRepo.isConfigured(this)) {
            showError(R.id.tvError, getString(R.string.err_firebase_missing));
            return;
        }

        String role = rbTeacher.isChecked() ? "teacher" : "student";
        final View btn = findViewById(R.id.btnSignUp);
        btn.setEnabled(false);
        showError(R.id.tvError, null);
        // Firebase reports a duplicate email itself, so no separate "email exists" lookup.
        CloudRepo.signUp(this, name, email, contact, password, role, new Callback<User>() {
            @Override public void onSuccess(User ignored) {
                // The account exists but stays locked until the emailed link is opened.
                open(VerifyEmailActivity.class);
                finish();
            }
            @Override public void onError(Exception e) {
                btn.setEnabled(true);
                showError(R.id.tvError, getString(CloudRepo.authErrorRes(e)));
            }
        });
    }
}
