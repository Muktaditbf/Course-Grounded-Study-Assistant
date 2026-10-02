package com.seu.studyassistant.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.User;

/** Entry point. Routes to the correct dashboard when a session already exists. */
public class SplashActivity extends BaseActivity {

    @Override
    protected boolean showsAiBubble() { return false; }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                User u = currentUser();
                if (u != null) {
                    openDashboard(u);
                } else {
                    Intent i = new Intent(SplashActivity.this, LoginActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                }
                finish();
            }
        }, 1200);
    }
}
