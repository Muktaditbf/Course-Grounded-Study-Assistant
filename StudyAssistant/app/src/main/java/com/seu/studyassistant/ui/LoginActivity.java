package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.seu.studyassistant.R;
import com.seu.studyassistant.data.Callback;
import com.seu.studyassistant.data.CloudRepo;
import com.seu.studyassistant.data.GoogleAuth;
import com.seu.studyassistant.model.User;

/**
 * UC2: Login to the System. SRS FR 1.1, 1.2, 1.3.
 *
 * Email and password, or Continue with Google. An email account whose address has not been
 * confirmed goes to the verify screen instead of the dashboard, and Forgot password emails a
 * reset link.
 */
public class LoginActivity extends BaseActivity {

    private EditText etEmail, etPassword;
    private View btnLogin, btnGoogle;

    @Override
    protected boolean showsAiBubble() { return false; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnGoogle = findViewById(R.id.btnGoogle);

        clearErrorWhileTyping(R.id.tvError, etEmail, etPassword);

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { attemptLogin(); }
        });
        btnGoogle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { googleSignIn(); }
        });
        findViewById(R.id.tvForgot).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { forgotPassword(); }
        });
        findViewById(R.id.tvGoSignUp).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(SignUpActivity.class); }
        });
    }

    private void setBusy(boolean busy) {
        btnLogin.setEnabled(!busy);
        btnGoogle.setEnabled(!busy);
    }

    // ------------------------------------------------------------- email/password

    private void attemptLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            showError(R.id.tvError, getString(R.string.err_fill_all));
            return;
        }
        if (!CloudRepo.isConfigured(this)) {
            showError(R.id.tvError, getString(R.string.err_firebase_missing));
            return;
        }

        setBusy(true);
        showError(R.id.tvError, null);
        // FR 1.2: Firebase Auth accepts only a correct email and password combination.
        CloudRepo.login(this, email, password, new Callback<User>() {
            @Override public void onSuccess(User u) {
                openDashboard(u);   // FR 1.3: role based routing
                finish();
            }
            @Override public void onError(Exception e) {
                setBusy(false);
                if (e instanceof CloudRepo.EmailNotVerified) {
                    open(VerifyEmailActivity.class);
                    return;
                }
                showError(R.id.tvError, getString(CloudRepo.authErrorRes(e)));
            }
        });
    }

    // ------------------------------------------------------------ forgot password

    private void forgotPassword() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setHint(R.string.email);
        input.setText(etEmail.getText());
        input.setSelection(input.getText().length());

        FrameLayout box = new FrameLayout(this);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_xl);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.forgot_title)
                .setMessage(R.string.forgot_body)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.forgot_send, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String email = input.getText().toString().trim();
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                input.setError(getString(R.string.err_bad_email));
                return;
            }
            v.setEnabled(false);
            CloudRepo.sendPasswordReset(this, email, new Callback<Void>() {
                @Override public void onSuccess(Void x) {
                    dialog.dismiss();
                    new AlertDialog.Builder(LoginActivity.this)
                            .setTitle(R.string.forgot_title)
                            .setMessage(getString(R.string.forgot_sent, email))
                            .setPositiveButton(R.string.close, null)
                            .show();
                }
                @Override public void onError(Exception e) {
                    v.setEnabled(true);
                    input.setError(getString(CloudRepo.authErrorRes(e)));
                }
            });
        }));
        dialog.show();
    }

    // ------------------------------------------------------------------- Google

    private void googleSignIn() {
        if (!CloudRepo.isConfigured(this)) {
            showError(R.id.tvError, getString(R.string.err_firebase_missing));
            return;
        }
        setBusy(true);
        showError(R.id.tvError, null);
        GoogleAuth.requestIdToken(this, new Callback<String>() {
            @Override public void onSuccess(String idToken) {
                CloudRepo.signInWithGoogle(LoginActivity.this, idToken, new Callback<User>() {
                    @Override public void onSuccess(User u) {
                        if (u != null) {
                            openDashboard(u);
                            finish();
                        } else {
                            chooseRole();   // first time with this Google account
                        }
                    }
                    @Override public void onError(Exception e) {
                        setBusy(false);
                        showError(R.id.tvError, getString(CloudRepo.authErrorRes(e)));
                    }
                });
            }
            @Override public void onError(Exception e) {
                setBusy(false);
                if (e instanceof GoogleAuth.Cancelled) return;
                showError(R.id.tvError, getString(e instanceof GoogleAuth.NotConfigured
                        ? R.string.err_google_not_setup : R.string.err_google_failed));
            }
        });
    }

    /** A first-time Google user picks Student or Teacher, exactly as on the sign-up form. */
    private void chooseRole() {
        final String[] roles = {"student", "teacher"};
        String[] labels = {getString(R.string.student), getString(R.string.teacher)};
        final int[] picked = {0};
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_role)
                .setCancelable(false)
                .setSingleChoiceItems(labels, 0, (d, which) -> picked[0] = which)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    CloudRepo.signOut(this);
                    setBusy(false);
                })
                .setPositiveButton(R.string.continue_label, (d, w) ->
                        CloudRepo.createGoogleProfile(this, roles[picked[0]], new Callback<User>() {
                            @Override public void onSuccess(User u) {
                                openDashboard(u);
                                finish();
                            }
                            @Override public void onError(Exception e) {
                                setBusy(false);
                                showError(R.id.tvError, getString(CloudRepo.authErrorRes(e)));
                            }
                        }))
                .show();
    }
}
