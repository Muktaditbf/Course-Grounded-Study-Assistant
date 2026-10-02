package com.seu.studyassistant.adapter;

import android.animation.ObjectAnimator;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;
import com.seu.studyassistant.model.Row;

import java.util.List;

/** One adapter reused by every list screen in the app. */
public class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

    public interface OnRowClick { void onRow(Row row); }
    public interface OnActionClick { void onAction(Row row); }

    /** Cycled so consecutive cards get distinct accent stripes. */
    private static final int[] ACCENTS = {
            R.color.accent_1, R.color.accent_2, R.color.accent_3, R.color.accent_4,
            R.color.accent_5, R.color.accent_6, R.color.accent_7};

    /**
     * The title view of the row most recently tapped. Screens that navigate to a detail
     * view hand this to a shared element transition, so the card's title flies into the
     * detail header instead of the screen cutting.
     */
    private View tappedTitle;

    private final List<Row> rows;
    private OnRowClick clickListener;
    private final OnActionClick actionListener;

    public RowAdapter(List<Row> rows, OnRowClick c) { this(rows, c, null); }

    public RowAdapter(List<Row> rows, OnRowClick c, OnActionClick a) {
        this.rows = rows; this.clickListener = c; this.actionListener = a;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final VH h, int position) {
        final Row r = rows.get(position);
        h.title.setText(r.title);

        int accent = ContextCompat.getColor(h.itemView.getContext(),
                ACCENTS[position % ACCENTS.length]);

        // A course row shows its subject illustration on an accent tile; every other list
        // keeps the original thin stripe, so no existing screen changes shape.
        if (r.iconRes != 0) {
            h.stripe.setVisibility(View.GONE);
            h.iconTile.setVisibility(View.VISIBLE);
            h.iconTile.getBackground().mutate().setTint(accent);
            h.icon.setImageResource(r.iconRes);
        } else {
            h.iconTile.setVisibility(View.GONE);
            h.stripe.setVisibility(View.VISIBLE);
            h.stripe.getBackground().mutate().setTint(accent);
        }

        if (r.subtitle == null || r.subtitle.isEmpty()) {
            h.subtitle.setVisibility(View.GONE);
        } else {
            h.subtitle.setVisibility(View.VISIBLE);
            h.subtitle.setText(r.subtitle);
        }

        if (r.badge == null || r.badge.isEmpty()) {
            h.badge.setVisibility(View.GONE);
        } else {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText(r.badge);
            if (r.badgeStyle == 1) {                       // approved / locked
                h.badge.setBackgroundResource(R.drawable.bg_chip_green);
                h.badge.setTextColor(ContextCompat.getColor(
                        h.itemView.getContext(), R.color.locked_green_text));
            } else if (r.badgeStyle == 3) {                // needs attention
                h.badge.setBackgroundResource(R.drawable.bg_chip_amber);
                h.badge.setTextColor(ContextCompat.getColor(
                        h.itemView.getContext(), R.color.declined_amber_text));
            } else {                                       // neutral / pending
                h.badge.setBackgroundResource(R.drawable.bg_chip_grey);
                h.badge.setTextColor(ContextCompat.getColor(
                        h.itemView.getContext(), R.color.text_secondary));
            }
        }

        if (r.action == null || actionListener == null) {
            h.action.setVisibility(View.GONE);
        } else {
            h.action.setVisibility(View.VISIBLE);
            h.action.setText(r.action);
            h.action.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { actionListener.onAction(r); }
            });
        }

        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tappedTitle = h.title;
                if (clickListener != null) clickListener.onRow(r);
            }
        });

        attachPressState(h.itemView);
    }

    /**
     * Press feedback: the card dips and lifts its shadow while held, and springs back on
     * release. The ripple comes from the layout's foreground, so this only adds the depth.
     *
     * OnTouchListener rather than a StateListAnimator because the card's background is a
     * plain shape drawable, and this keeps the behaviour identical on every API level.
     */
    private void attachPressState(final View card) {
        final float lift = card.getResources().getDimension(R.dimen.card_press_elevation);
        card.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        press(v, 0.97f, lift);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        press(v, 1f, 0f);
                        break;
                    default:
                        break;
                }
                // Never consume: the click listener and the ripple both still need the event.
                return false;
            }
        });
    }

    private static void press(View v, float scale, float elevation) {
        v.animate().cancel();
        v.animate()
                .scaleX(scale).scaleY(scale)
                .setDuration(110)
                .start();
        ObjectAnimator.ofFloat(v, "translationZ", v.getTranslationZ(), elevation)
                .setDuration(110)
                .start();
    }

    @Override
    public int getItemCount() { return rows.size(); }

    /** Valid only inside an OnRowClick callback; null before the first tap. */
    public View tappedTitle() { return tappedTitle; }

    /** Lets a caller install a listener that needs to reference the adapter itself. */
    public void setOnRowClick(OnRowClick c) { this.clickListener = c; }

    static class VH extends RecyclerView.ViewHolder {
        TextView title, subtitle, badge;
        View stripe;
        FrameLayout iconTile;
        ImageView icon;
        Button action;
        VH(View v) {
            super(v);
            title = v.findViewById(R.id.rowTitle);
            subtitle = v.findViewById(R.id.rowSubtitle);
            badge = v.findViewById(R.id.rowBadge);
            stripe = v.findViewById(R.id.rowStripe);
            iconTile = v.findViewById(R.id.rowIconTile);
            icon = v.findViewById(R.id.rowIcon);
            action = v.findViewById(R.id.rowAction);
        }
    }
}
