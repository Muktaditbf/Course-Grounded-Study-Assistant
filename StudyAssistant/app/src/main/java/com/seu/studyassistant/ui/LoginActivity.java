package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.User;

/** UC2: Login to the System. SRS FR 1.1, 1.2, 1.3. */
public class LoginActivity extends BaseActivity {

    private EditText etEmail, etPassword;

    @Override
    protected boolean showsAiBubble() { return false; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);

        findViewById(R.id.btnLogin).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { attemptLogin(); }
        });

        ((TextView) findViewById(R.id.tvGoSignUp)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(SignUpActivity.class); }
        });
    }

    private void attemptLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            showError(R.id.tvError, getString(R.string.err_fill_all));
            return;
        }

        // FR 1.2: login succeeds only on a correct email and password combination.
        User u = db.login(email, password);
        if (u == null) {
            showError(R.id.tvError, getString(R.string.err_bad_credentials));
            return;
        }

        showError(R.id.tvError, null);
        session.login(u.id);
        openDashboard(u);   // FR 1.3: role based routing
        finish();
    }
}
