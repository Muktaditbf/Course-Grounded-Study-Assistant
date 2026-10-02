package com.seu.studyassistant.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.User;

/** UC10 step 1 and 2: choose Student Premium or an Institutional License. */
public class BillingActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_billing);
        setupHeader(getString(R.string.billing), true);

        ((TextView) findViewById(R.id.tvFreeFeatures))
                .setText(getString(R.string.free_features, FREE_DAILY_LIMIT));

        findViewById(R.id.btnPremium).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startPayment("premium"); }
        });
        findViewById(R.id.btnInstitutional).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startPayment("institutional"); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        User u = currentUser();
        if (u == null) { logout(); return; }

        String label;
        if ("premium".equals(u.tier)) label = getString(R.string.tier_premium);
        else if ("institutional".equals(u.tier)) label = getString(R.string.tier_institutional);
        else label = getString(R.string.tier_free);

        ((TextView) findViewById(R.id.tvCurrentTier))
                .setText(getString(R.string.current_tier_format, label));
    }

    private void startPayment(String plan) {
        Intent i = new Intent(this, PaymentActivity.class);
        i.putExtra(EXTRA_PLAN, plan);
        startActivity(i);
    }
}
