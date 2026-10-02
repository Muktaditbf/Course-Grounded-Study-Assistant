package com.seu.studyassistant.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;
import com.seu.studyassistant.adapter.RowAdapter;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.Row;
import com.seu.studyassistant.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * UC6 / FR 6.1: keyword search across enrolled courses, without going through the AI.
 *
 * Results filter as you type. Input is debounced so a query only runs once the student
 * pauses, rather than on every keystroke.
 */
public class SearchActivity extends BaseActivity {

    /** Long enough to skip mid-word queries, short enough to still feel live. */
    private static final long DEBOUNCE_MS = 300;

    /** Material type each filter chip selects; null means every type. */
    private static final String[] FILTER_TYPES =
            {null, "lecture", "lab", "assignment", "quiz", "notes"};

    private EditText etSearch;
    private RecyclerView recycler;
    private TextView tvEmpty;
    private LinearLayout recentBlock, recentChips, filterChips;

    private final List<Row> rows = new ArrayList<>();
    private RowAdapter adapter;

    private final Handler debounce = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private int activeFilter = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        setupHeader(getString(R.string.search), true);

        etSearch = findViewById(R.id.etSearch);
        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        recentBlock = findViewById(R.id.recentBlock);
        recentChips = findViewById(R.id.recentChips);
        filterChips = findViewById(R.id.filterChips);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        Anim.stagger(recycler);
        setupBottomNav(R.id.nav_search);

        adapter = new RowAdapter(rows, new RowAdapter.OnRowClick() {
            @Override public void onRow(Row row) {
                open(MaterialViewActivity.class, EXTRA_MATERIAL_ID, row.id);
            }
        });
        recycler.setAdapter(adapter);

        buildFilterChips();
        showIdleState();

        findViewById(R.id.btnSearch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runSearch(true); }
        });

        // Filter as you type: every edit reschedules the query DEBOUNCE_MS into the future,
        // so a fast typist triggers one search instead of one per character.
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { scheduleSearch(); }
        });
    }

    @Override
    protected void onDestroy() {
        if (pending != null) debounce.removeCallbacks(pending);
        super.onDestroy();
    }

    private void scheduleSearch() {
        if (pending != null) debounce.removeCallbacks(pending);
        pending = new Runnable() {
            @Override public void run() { runSearch(false); }
        };
        debounce.postDelayed(pending, DEBOUNCE_MS);
    }

    // -------------------------------------------------------------------- states

    /** Before the first query: offer what they searched last rather than a blank screen. */
    private void showIdleState() {
        rows.clear();
        adapter.notifyDataSetChanged();

        String[] recents = session.recentSearches();
        recentBlock.setVisibility(recents.length == 0 ? View.GONE : View.VISIBLE);
        recentChips.removeAllViews();

        for (final String q : recents) {
            recentChips.addView(chip(q, false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    etSearch.setText(q);
                    etSearch.setSelection(q.length());
                    runSearch(true);
                }
            }));
        }

        tvEmpty.setText(getString(R.string.search_prompt));
        tvEmpty.setVisibility(View.VISIBLE);
    }

    private void buildFilterChips() {
        filterChips.removeAllViews();
        String[] labels = {
                getString(R.string.filter_all), getString(R.string.type_lecture),
                getString(R.string.type_lab), getString(R.string.type_assignment),
                getString(R.string.type_quiz), getString(R.string.type_notes)};

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            filterChips.addView(chip(labels[i], i == activeFilter, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (activeFilter == index) return;
                    activeFilter = index;
                    buildFilterChips();
                    runSearch(false);
                }
            }));
        }
    }

    /** One chip, styled from the existing chip drawables. */
    private TextView chip(String label, boolean selected, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackgroundResource(selected ? R.drawable.bg_chip_soft : R.drawable.bg_chip_grey);
        t.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.primary : R.color.text_secondary));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);

        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(onClick);
        return t;
    }

    // -------------------------------------------------------------------- search

    private void runSearch(boolean remember) {
        User u = currentUser();
        if (u == null) { logout(); return; }

        String keyword = etSearch.getText().toString().trim();
        if (keyword.isEmpty()) { showIdleState(); return; }

        recentBlock.setVisibility(View.GONE);
        if (remember) session.addRecentSearch(keyword);

        List<Material> results = db.searchMaterials(u.id, keyword);
        String wanted = FILTER_TYPES[activeFilter];

        rows.clear();
        for (Material m : results) {
            if (wanted != null && !wanted.equals(m.type)) continue;
            Course c = db.courseById(m.courseId);
            rows.add(new Row(m.id, m.title,
                    (c == null ? "" : c.code + "  •  ") + getString(m.typeLabelRes()), null, 0));
        }
        adapter.notifyDataSetChanged();

        // Name the query back to the student so an empty result is obviously about the query.
        tvEmpty.setText(getString(R.string.no_results_for, keyword));
        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (!rows.isEmpty()) Anim.replay(recycler);
    }
}
