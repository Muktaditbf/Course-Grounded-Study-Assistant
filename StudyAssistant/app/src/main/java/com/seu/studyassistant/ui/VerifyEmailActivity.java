package com.seu.studyassistant.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseUser;
import com.seu.studyassistant.R;
import com.seu.studyassistant.data.Callback;
import com.seu.studyassistant.data.CloudRepo;
import com.seu.studyassistant.model.User;

/**
 * Confirms the account's email is real before the app can be used.
 *
 * Sign-up sends a verification link; this screen waits for the user to open it. "I've
 * verified" re-checks with Firebase, and only then is the user signed in to the app. The
 * Firestore rules enforce the same thing server-side, so an unverified account cannot read
 * or write any course data even with a modified app.
 */
public class VerifyEmailActivity extends BaseActivity {

    /** Firebase rate-limits verification emails; one resend a minute keeps clear of that. */
    private static final long RESEND_COOLDOWN_MS = 60_000;

    private Button btnResend, btnVerified;
    private CountDownTimer cooldown;

    @Override
    protected boolean showsAiBubble() { return false; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_verify_email);

        FirebaseUser fu = CloudRepo.firebaseUser(this);
        if (fu == null) { backToLogin(); return; }

        ((TextView) findViewById(R.id.tvVerifyBody)).setText(
                getString(R.string.verify_body, fu.getEmail() == null ? "" : fu.getEmail()));

        btnVerified = findViewById(R.id.btnVerified);
        btnResend = findViewById(R.id.btnResend);

        btnVerified.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { checkVerified(); }
        });
        btnResend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resend(); }
        });
        findViewById(R.id.tvBackToLogin).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CloudRepo.signOut(VerifyEmailActivity.this);
                backToLogin();
            }
        });

        // Sign-up has just sent the first email.
        startCooldown();
    }

    private void checkVerified() {
        btnVerified.setEnabled(false);
        showError(R.id.tvError, null);
        CloudRepo.continueAfterVerification(this, new Callback<User>() {
            @Override public void onSuccess(User u) {
                openDashboard(u);
                finish();
            }
            @Override public void onError(Exception e) {
                btnVerified.setEnabled(true);
                showError(R.id.tvError, getString(CloudRepo.authErrorRes(e)));
            }
        });
    }

    private void resend() {
        btnResend.setEnabled(false);
        CloudRepo.resendVerification(this, new Callback<Void>() {
            @Override public void onSuccess(Void v) {
                toast(getString(R.string.verify_sent));
                startCooldown();
            }
            @Override public void onError(Exception e) {
                btnResend.setEnabled(true);
                showError(R.id.tvError, getString(CloudRepo.authErrorRes(e)));
            }
        });
    }

    private void startCooldown() {
        btnResend.setEnabled(false);
        if (cooldown != null) cooldown.cancel();
        cooldown = new CountDownTimer(RESEND_COOLDOWN_MS, 1000) {
            @Override public void onTick(long left) {
                btnResend.setText(getString(R.string.verify_resend_in, (int) (left / 1000) + 1));
            }
            @Override public void onFinish() {
                btnResend.setText(R.string.verify_resend);
                btnResend.setEnabled(true);
            }
        }.start();
    }

    private void backToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (cooldown != null) cooldown.cancel();
        super.onDestroy();
    }
}
