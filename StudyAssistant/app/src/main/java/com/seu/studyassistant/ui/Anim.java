package com.seu.studyassistant.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.seu.studyassistant.R;

/**
 * Every animation in the app, in one place.
 *
 * Motion here is deliberately short - nothing exceeds 380ms - because the app is demonstrated
 * on a projector where slow transitions read as lag. Each helper is a no-op when the user has
 * turned animations off in Settings, so the app stays usable for anyone who needs that.
 */
public final class Anim {

    /** Standard durations, in milliseconds. */
    public static final int FAST = 160;
    public static final int MEDIUM = 240;
    public static final int SLOW = 380;

    private Anim() {}

    /** Honours Settings > Developer options / Accessibility "Remove animations". */
    public static boolean enabled(Context c) {
        try {
            float scale = Settings.Global.getFloat(
                    c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale > 0f;
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------- entrances

    /** Staggered fade-and-rise for a RecyclerView. Call before setting the adapter. */
    public static void stagger(RecyclerView list) {
        if (list == null) return;
        if (!enabled(list.getContext())) return;
        list.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(
                list.getContext(), R.anim.layout_stagger_up));
    }

    /** Replays the stagger after the data set changes. */
    public static void replay(RecyclerView list) {
        if (list == null || list.getLayoutAnimation() == null) return;
        list.scheduleLayoutAnimation();
    }

    /** Gradient hero: settles down into place as the screen opens. */
    public static void heroIn(View hero) {
        if (hero == null || !enabled(hero.getContext())) return;
        hero.setAlpha(0f);
        hero.setTranslationY(-hero.getResources().getDisplayMetrics().density * 16);
        hero.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(MEDIUM)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    /** Brief emphasis, used when a value the user just caused has changed. */
    public static void pulse(View v) {
        if (v == null || !enabled(v.getContext())) return;
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.setAlpha(0.4f);
        v.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(MEDIUM)
                .setInterpolator(new OvershootInterpolator(1.4f))
                .start();
    }

    /**
     * Bookmark toggle: a quick 1 -> 1.3 -> 1 scale so saving something feels physical.
     * Runs on the button itself, so it works wherever the toggle lives.
     */
    public static void bounce(final View v) {
        if (v == null || !enabled(v.getContext())) return;
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.animate()
                .scaleX(1.3f).scaleY(1.3f)
                .setDuration(FAST)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        v.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(MEDIUM)
                                .setInterpolator(new OvershootInterpolator(3f))
                                .start();
                    }
                })
                .start();
    }

    // ------------------------------------------------------------ the content lock

    /**
     * The Content Lock morph: a Pending chip becoming a Locked chip, or the reverse.
     *
     * The chip's background colour is evaluated from one state to the other while the chip
     * dips and springs back, so the lock reads as physically snapping shut. The row is never
     * rebuilt, so the list keeps its scroll position.
     */
    public static void morphChip(final TextView chip, int fromColorRes, int toColorRes,
                                 int toTextColorRes, String newLabel, int newBackgroundRes) {
        if (chip == null) return;
        final Context ctx = chip.getContext();

        if (!enabled(ctx)) {
            chip.setText(newLabel);
            chip.setBackgroundResource(newBackgroundRes);
            chip.setTextColor(ContextCompat.getColor(ctx, toTextColorRes));
            return;
        }

        int from = ContextCompat.getColor(ctx, fromColorRes);
        int to = ContextCompat.getColor(ctx, toColorRes);

        // Swap the label at the midpoint, where the chip is smallest and the change is least jarring.
        chip.setText(newLabel);
        chip.setTextColor(ContextCompat.getColor(ctx, toTextColorRes));

        ValueAnimator colour = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        colour.setDuration(SLOW);
        colour.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                chip.getBackground().mutate().setTint((Integer) a.getAnimatedValue());
            }
        });

        chip.setBackgroundResource(newBackgroundRes);
        chip.getBackground().mutate().setTint(from);
        colour.start();

        chip.animate().cancel();
        chip.setScaleX(0.7f);
        chip.setScaleY(0.7f);
        chip.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(SLOW)
                .setInterpolator(new OvershootInterpolator(2.2f))
                .start();
    }

    /** A short colour wash across a row, confirming which row the teacher just changed. */
    public static void flashRow(final View row, int colorRes) {
        if (row == null || !enabled(row.getContext())) return;
        final int tint = ContextCompat.getColor(row.getContext(), colorRes);

        ValueAnimator wash = ValueAnimator.ofFloat(0f, 1f);
        wash.setDuration(SLOW + 120);
        wash.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                // Rise to a light wash, then fall back to nothing.
                float strength = f < 0.4f ? f / 0.4f : (1f - f) / 0.6f;
                row.setBackgroundColor(applyAlpha(tint, 0.16f * strength));
            }
        });
        wash.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                row.setBackgroundResource(R.drawable.bg_card);
            }
        });
        wash.start();
    }

    private static int applyAlpha(int color, float alpha) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    // ---------------------------------------------------------------------- numbers

    /** Counts a stat tile up from zero, so dashboards feel alive rather than static. */
    public static void countUp(final TextView view, final int target) {
        if (view == null) return;
        if (!enabled(view.getContext()) || target <= 0) {
            view.setText(String.valueOf(target));
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(0, target);
        a.setDuration(Math.min(SLOW + 200, 260 + target * 18));
        a.setInterpolator(new DecelerateInterpolator(1.8f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                view.setText(String.valueOf(v.getAnimatedValue()));
            }
        });
        a.start();
    }

    /** Same, for a percentage that already carries its own suffix. */
    public static void countUpPercent(final TextView view, final int target) {
        if (view == null) return;
        if (!enabled(view.getContext())) {
            view.setText(target + "%");
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(0, target);
        a.setDuration(SLOW + 200);
        a.setInterpolator(new DecelerateInterpolator(1.8f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                view.setText(v.getAnimatedValue() + "%");
            }
        });
        a.start();
    }
}
