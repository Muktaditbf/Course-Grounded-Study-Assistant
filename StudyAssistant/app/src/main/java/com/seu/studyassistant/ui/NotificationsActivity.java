package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;
import com.seu.studyassistant.adapter.RowAdapter;
import com.seu.studyassistant.model.Row;
import com.seu.studyassistant.model.User;

import java.util.ArrayList;
import java.util.List;

/** FR 9 / 9.1: notifications for new materials, answers and account events. */
public class NotificationsActivity extends BaseActivity {

    private RecyclerView recycler;
    private TextView tvEmpty;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        setupHeader(getString(R.string.notifications), true);

        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvEmpty.setText(getString(R.string.no_notifications));
        recycler.setLayoutManager(new LinearLayoutManager(this));
        Anim.stagger(recycler);
    }

    @Override
    protected void onResume() {
        super.onResume();
        User u = currentUser();
        if (u == null) { logout(); return; }

        List<String[]> items = db.notifications(u.id);
        List<Row> rows = new ArrayList<>();
        long i = 0;
        for (String[] n : items) {
            rows.add(new Row(i++, n[0], n[1], null, 0));
        }

        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setAdapter(new RowAdapter(rows, null));

        // Opening this screen is what "seen" means, so the dashboard badge clears on the
        // way back. The watermark is the newest id, not the count, so anything that arrives
        // while this screen is open still counts as unread.
        session.setLastSeenNotification(u.id, db.newestNotificationId(u.id));
    }
}
