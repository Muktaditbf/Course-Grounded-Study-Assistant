package com.seu.studyassistant.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.seu.studyassistant.R;
import com.seu.studyassistant.adapter.RowAdapter;
import com.seu.studyassistant.model.Course;
import com.seu.studyassistant.model.Material;
import com.seu.studyassistant.model.Row;
import com.seu.studyassistant.model.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * UC7 / FR 7.1: the saved list a student revisits.
 *
 * Swiping a card removes the bookmark, with an undo Snackbar so a mis-swipe costs nothing.
 * The list can be read newest-first or grouped by course, whichever suits revision.
 */
public class BookmarksActivity extends BaseActivity {

    private RecyclerView recycler;
    private TextView tvEmpty, tvHint;

    private final List<Row> rows = new ArrayList<>();
    private RowAdapter adapter;
    private List<Material> saved = new ArrayList<>();

    /** false = newest first, true = grouped by course. */
    private boolean sortBySubject = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        setupHeader(getString(R.string.bookmarks), true);

        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvHint = findViewById(R.id.tvHint);
        tvEmpty.setText(getString(R.string.no_bookmarks_hint));
        recycler.setLayoutManager(new LinearLayoutManager(this));
        Anim.stagger(recycler);

        setupBottomNav(R.id.nav_saved);

        adapter = new RowAdapter(rows, new RowAdapter.OnRowClick() {
            @Override public void onRow(Row row) {
                open(MaterialViewActivity.class, EXTRA_MATERIAL_ID, row.id);
            }
        });
        recycler.setAdapter(adapter);

        // Sort toggle lives in the header action, where every other screen puts its one action.
        setHeaderAction(getString(R.string.sort_by_recent), new View.OnClickListener() {
            @Override public void onClick(View v) {
                sortBySubject = !sortBySubject;
                setHeaderAction(getString(sortBySubject
                        ? R.string.sort_by_subject : R.string.sort_by_recent), this);
                load();
                Anim.replay(recycler);
            }
        });

        attachSwipeToRemove();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentUser() == null) { logout(); return; }
        load();
    }

    private void load() {
        User u = currentUser();
        if (u == null) return;

        saved = db.bookmarks(u.id);
        if (sortBySubject) {
            Collections.sort(saved, new Comparator<Material>() {
                @Override public int compare(Material a, Material b) {
                    return Long.compare(a.courseId, b.courseId);
                }
            });
        }

        rows.clear();
        for (Material m : saved) {
            Course c = db.courseById(m.courseId);
            rows.add(new Row(m.id, m.title,
                    (c == null ? "" : c.code + "  •  ") + getString(m.typeLabelRes()), null, 0));
        }

        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        tvHint.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ swipe to remove

    private void attachSwipeToRemove() {
        final Drawable icon = ContextCompat.getDrawable(this, R.drawable.ic_bookmark);
        final int red = ContextCompat.getColor(this, R.color.danger);

        ItemTouchHelper helper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(0,
                        ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder a,
                                  @NonNull RecyclerView.ViewHolder b) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int position = vh.getBindingAdapterPosition();
                if (position < 0 || position >= rows.size()) return;
                removeAt(position);
            }

            /** Paints a red wash behind the card as it slides away. */
            @Override
            public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                View item = vh.itemView;
                float progress = Math.min(1f, Math.abs(dX) / (item.getWidth() * 0.5f));
                int alpha = Math.round(255 * 0.18f * progress);
                canvas.save();
                canvas.clipRect(item.getLeft(), item.getTop(), item.getRight(), item.getBottom());
                canvas.drawColor((alpha << 24) | (red & 0x00FFFFFF));
                canvas.restore();

                item.setAlpha(1f - progress * 0.4f);
                super.onChildDraw(canvas, rv, vh, dX, dY, actionState, isCurrentlyActive);
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                vh.itemView.setAlpha(1f);
                super.clearView(rv, vh);
            }
        });
        helper.attachToRecyclerView(recycler);
    }

    private void removeAt(int position) {
        User u = currentUser();
        if (u == null) return;

        final Material removed = saved.get(position);
        db.toggleBookmark(u.id, removed.id);   // the row is gone from the table now

        saved.remove(position);
        rows.remove(position);
        adapter.notifyItemRemoved(position);
        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);

        Snackbar bar = Snackbar.make(recycler, getString(R.string.bookmark_removed),
                        Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.undo), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        User me = currentUser();
                        if (me == null) return;
                        db.toggleBookmark(me.id, removed.id);   // put it straight back
                        load();
                    }
                });

        // Left unanchored, the bar sits on top of the bottom navigation and Undo lands on
        // the same pixels as the Settings tab.
        View nav = findViewById(R.id.bottomNav);
        if (nav != null && nav.getVisibility() == View.VISIBLE) bar.setAnchorView(nav);
        bar.show();
    }
}
