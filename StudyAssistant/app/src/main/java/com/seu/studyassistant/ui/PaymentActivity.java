package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.User;

/**
 * UC10 steps 3 to 5, plus alternative course 4.a (payment failure).
 *
 * This is a SIMULATED gateway. No real bKash, Nagad or card network is contacted and no
 * payment credential is transmitted or stored; the account number is only length checked.
 */
public class PaymentActivity extends BaseActivity {

    private String plan;
    private EditText etAccount;
    private View failBlock;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment);
        setupHeader(getString(R.string.confirm_payment), true);

        plan = getIntent().getStringExtra(EXTRA_PLAN);
        if (plan == null) plan = "premium";

        etAccount = findViewById(R.id.etAccount);
        failBlock = findViewById(R.id.failBlock);

        boolean premium = "premium".equals(plan);
        ((TextView) findViewById(R.id.tvPlan)).setText(premium
                ? getString(R.string.tier_premium) : getString(R.string.tier_institutional));
        ((TextView) findViewById(R.id.tvAmount)).setText(premium
                ? getString(R.string.price_premium) : getString(R.string.price_institutional));

        findViewById(R.id.btnPay).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pay(); }
        });
    }

    private void pay() {
        User u = currentUser();
        if (u == null) { logout(); return; }

        String account = etAccount.getText().toString().trim();

        // UC10 alternative course 4.a: the payment fails and the user may retry.
        if (account.length() != 11) {
            failBlock.setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.tvFailReason))
                    .setText(getString(R.string.payment_invalid_account));
            ((TextView) findViewById(R.id.btnPay)).setText(getString(R.string.retry_payment));
            return;
        }

        failBlock.setVisibility(View.GONE);
        db.setTier(u.id, plan);

        String label = "premium".equals(plan)
                ? getString(R.string.tier_premium) : getString(R.string.tier_institutional);
        db.notify(u.id, getString(R.string.payment_success), getString(R.string.upgrade_done, label));

        toast(getString(R.string.upgrade_done, label));
        finish();
    }

    /** Reports which method the student selected; kept for the simulated receipt. */
    private String selectedMethod() {
        RadioButton nagad = findViewById(R.id.rbNagad);
        RadioButton card = findViewById(R.id.rbCard);
        if (nagad.isChecked()) return getString(R.string.nagad);
        if (card.isChecked()) return getString(R.string.card);
        return getString(R.string.bkash);
    }
}
