package com.seu.studyassistant.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;

import com.seu.studyassistant.R;
import com.seu.studyassistant.data.CloudRepo;
import com.seu.studyassistant.data.DatabaseHelper;
import com.seu.studyassistant.data.SessionManager;
import com.seu.studyassistant.data.SyncManager;
import com.seu.studyassistant.model.User;

/** Shared plumbing: session, database, the app bar, bottom navigation, and card building. */
public abstract class BaseActivity extends AppCompatActivity {

    /** Free tier daily AI question cap. The Cost Report says "capped" but never gives a number. */
    public static final int FREE_DAILY_LIMIT = 10;

    public static final String EXTRA_COURSE_ID = "courseId";
    public static final String EXTRA_MATERIAL_ID = "materialId";
    public static final String EXTRA_PLAN = "plan";

    /** Marks an activity that was entered through a shared element, so it exits the same way. */
    private static final String EXTRA_SHARED = "enteredShared";

    /** True when this screen was opened with a card-to-header morph. */
    protected boolean enteredShared() {
        return getIntent().getBooleanExtra(EXTRA_SHARED, false);
    }

    /** The one shared element name in the app: a list card's title becoming a detail header. */
    public static final String SHARED_TITLE = "sharedTitle";

    protected DatabaseHelper db;
    protected SessionManager session;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = DatabaseHelper.get(this);
        session = new SessionManager(this);

        // Apply the theme the user chose in Settings before anything inflates, so no screen
        // ever flashes the wrong palette on the way in.
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(session.themeMode());

        applySavedLanguage();
        ensureSync();
    }

    // ----------------------------------------------------------------------- sync

    /**
     * Restarts the Firestore mirror after a process restart. Login starts it, but when Android
     * kills the process and brings the user straight back into a deep screen no login runs, so
     * every screen makes sure the listeners are up. Idempotent.
     */
    private void ensureSync() {
        String uid = CloudRepo.currentUid(this);
        if (uid == null || !session.isLoggedIn() || SyncManager.get().isRunningFor(uid)) return;
        User u = db.userById(session.userId());
        if (u != null) SyncManager.get().start(this, uid, u.role);
    }

    private final Runnable dataListener = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && !isDestroyed()) onDataChanged();
        }
    };

    private long lastSyncToast;

    private final SyncManager.ErrorListener errorListener = new SyncManager.ErrorListener() {
        @Override public void onSyncError(String message) {
            // One toast every few seconds is enough; a failing listener can report repeatedly.
            long now = System.currentTimeMillis();
            if (now - lastSyncToast < 8000 || isFinishing() || isDestroyed()) return;
            lastSyncToast = now;
            Toast.makeText(BaseActivity.this, getString(R.string.sync_error, message),
                    Toast.LENGTH_LONG).show();
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        SyncManager.get().addChangeListener(dataListener);
        SyncManager.get().addErrorListener(errorListener);
    }

    @Override
    protected void onStop() {
        SyncManager.get().removeChangeListener(dataListener);
        SyncManager.get().removeErrorListener(errorListener);
        super.onStop();
    }

    /**
     * Called when Firestore delivered new data into the local mirror while this screen is
     * visible. List screens override it to reload; screens that only read on demand ignore it.
     */
    protected void onDataChanged() {}

    /**
     * Re-applies the saved UI language on every cold start.
     *
     * Android 13+ remembers a per-app language itself, but below that AppCompat does not
     * persist it, so the choice would silently revert on relaunch. Reading it back from
     * SessionManager here covers both, and the guard matters: setApplicationLocales triggers
     * an activity recreate, so calling it when nothing changed would loop.
     */
    private void applySavedLanguage() {
        String saved = session.language();
        LocaleListCompat wanted = SessionManager.LANG_SYSTEM.equals(saved)
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(saved);

        if (!AppCompatDelegate.getApplicationLocales().equals(wanted)) {
            AppCompatDelegate.setApplicationLocales(wanted);
        }
    }

    /**
     * The signed-in user, or null. A saved session only counts while Firebase still holds a
     * signed-in account, so a revoked or expired login sends every screen back to Login.
     */
    protected User currentUser() {
        long id = session.userId();
        if (id <= 0) return null;
        if (CloudRepo.isConfigured(this) && CloudRepo.currentUid(this) == null) return null;
        return db.userById(id);
    }

    // -------------------------------------------------------------- AI bubble

    /**
     * Drops the floating assistant onto every student screen.
     *
     * Done by overriding setContentView rather than editing a dozen layouts: the bubble has to
     * be reachable from wherever a question occurs to the student, and adding it per-layout
     * would guarantee it goes missing from one of them. It sits above the bottom navigation
     * when a screen has one, and is hidden for teachers, who upload rather than revise.
     */
    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        applyEdgeToEdge();
        attachAiBubble();
    }

    /**
     * Draws the app behind the status and navigation bars (enforced anyway for apps targeting
     * Android 15+), then pads the screen's root so nothing sits under them. The keyboard is
     * part of the bottom inset, so text fields stay visible while typing on every Android version.
     */
    private void applyEdgeToEdge() {
        ViewGroup frame = findViewById(android.R.id.content);
        if (frame == null || frame.getChildCount() == 0) return;
        final View content = frame.getChildAt(0);

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat bars =
                new androidx.core.view.WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!night);
        bars.setAppearanceLightNavigationBars(!night);

        final int l = content.getPaddingLeft(), t = content.getPaddingTop();
        final int r = content.getPaddingRight(), b = content.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(l + sys.left, t + sys.top, r + sys.right, b + Math.max(sys.bottom, ime.bottom));
            return insets;
        });
    }

    /** Frosted-glass blur of the screen behind a floating control. */
    private void setupGlass(eightbitlab.com.blurview.BlurView glass, View behind) {
        if (glass == null || !(behind instanceof ViewGroup)) return;
        glass.setupWith((ViewGroup) behind)
                .setFrameClearDrawable(getWindow().getDecorView().getBackground())
                .setBlurRadius(20f)
                .setOverlayColor(ContextCompat.getColor(this, R.color.glass_overlay));
        glass.setClipToOutline(true);
    }

    /** Screens that must not show it: the assistant itself, and anything pre-login. */
    protected boolean showsAiBubble() { return true; }

    private void attachAiBubble() {
        if (!showsAiBubble()) return;

        User u = currentUser();
        if (u == null || u.isTeacher()) return;

        ViewGroup root = findViewById(android.R.id.content);
        if (root == null || root.getChildCount() == 0) return;
        View content = root.getChildAt(0);
        if (!(content instanceof ViewGroup)) return;

        // ViVi's round glass button, the same one the floating navigation carries.
        int size = getResources().getDimensionPixelSize(R.dimen.nav_round_size);
        eightbitlab.com.blurview.BlurView glass = new eightbitlab.com.blurview.BlurView(this);
        glass.setId(R.id.fabAi);
        glass.setBackgroundResource(R.drawable.bg_glass_round);
        glass.setElevation(dp(12));
        android.widget.ImageView face = new android.widget.ImageView(this);
        face.setImageResource(R.drawable.ic_vivi);
        face.setPadding(dp(11), dp(11), dp(11), dp(11));
        face.setContentDescription(getString(R.string.ai_chat));
        face.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(ChatActivity.class); }
        });
        glass.addView(face, new FrameLayout.LayoutParams(size, size));

        final FrameLayout overlay = new FrameLayout(this);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lp.rightMargin = getResources().getDimensionPixelSize(R.dimen.nav_float_margin);
        overlay.addView(glass, lp);
        liftAboveGestureBar(glass, lp);
        setupGlass(glass, content);

        // The overlay must not swallow taps meant for the screen underneath.
        overlay.setClickable(false);
        overlay.setFocusable(false);

        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        keepLastItemClearOf(content);
    }

    /**
     * Adds bottom padding to whatever scrolls, so the assistant never sits on top of the last
     * card. clipToPadding is turned off as well, otherwise the padding would clip the content
     * mid-scroll instead of acting as a run-out.
     */
    private void keepLastItemClearOf(View content) {
        // Pad whichever view actually scrolls. On the dashboard that is a NestedScrollView
        // wrapping a non-scrolling RecyclerView, so padding the list itself would do nothing.
        ViewGroup scroller = findScroller(content);
        if (scroller == null) return;

        // The original padding is remembered, so a screen that gets both the ViVi button and
        // the navigation bar is padded once for the larger of the two, not twice.
        Object base = scroller.getTag(R.id.tag_base_padding);
        int basePadding = base instanceof Integer ? (Integer) base : scroller.getPaddingBottom();
        scroller.setTag(R.id.tag_base_padding, basePadding);
        scroller.setClipToPadding(false);
        scroller.setPadding(scroller.getPaddingLeft(), scroller.getPaddingTop(),
                scroller.getPaddingRight(),
                basePadding + getResources().getDimensionPixelSize(R.dimen.nav_clearance));
    }

    /** Depth-first search for the nearest scrolling container. */
    private ViewGroup findScroller(View v) {
        if (v instanceof androidx.core.widget.NestedScrollView
                || v instanceof android.widget.ScrollView
                || v instanceof androidx.recyclerview.widget.RecyclerView) {
            return (ViewGroup) v;
        }
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) v;
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup found = findScroller(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    // ------------------------------------------------------------------ chrome

    /** Wires the included view_header: sets the title and makes the back arrow work. */
    protected void setupHeader(String title, boolean showBack) {
        TextView t = findViewById(R.id.headerTitle);
        if (t != null) t.setText(title);

        View back = findViewById(R.id.btnBack);
        if (back != null) {
            back.setVisibility(showBack ? View.VISIBLE : View.GONE);
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
        }
    }

    protected void setHeaderAction(String label, View.OnClickListener listener) {
        TextView a = findViewById(R.id.headerAction);
        if (a == null) return;
        a.setVisibility(View.VISIBLE);
        a.setText(label);
        a.setOnClickListener(listener);
    }

    /**
     * Wires the student bottom navigation. Each destination is its own Activity, so the
     * selected tab is passed in and re-selecting it is a no-op.
     */
    protected void setupBottomNav(final int selectedId) {
        // The bar is the STUDENT shell. A teacher reaching a shared screen (Notifications)
        // must not be offered Home / Search / Saved / Progress, which all belong to the
        // student role and would drop them into the wrong dashboard.
        User u = currentUser();
        if (u == null || u.isTeacher()) return;

        ViewGroup frame = findViewById(android.R.id.content);
        if (frame == null || frame.getChildCount() == 0 || findViewById(R.id.floatingNav) != null) return;
        View content = frame.getChildAt(0);

        // The navigation carries ViVi's round button, so the standalone one goes.
        View loose = findViewById(R.id.fabAi);
        if (loose != null && loose.getParent() instanceof View) frame.removeView((View) loose.getParent());

        View nav = getLayoutInflater().inflate(R.layout.view_floating_nav, frame, false);
        int margin = getResources().getDimensionPixelSize(R.dimen.nav_float_margin);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM;
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        frame.addView(nav, lp);
        liftAboveGestureBar(nav, lp);

        setupGlass((eightbitlab.com.blurview.BlurView) nav.findViewById(R.id.navBlur), content);
        setupGlass((eightbitlab.com.blurview.BlurView) nav.findViewById(R.id.viviBlur), content);
        keepLastItemClearOf(content);

        final int[][] tabs = {
                {R.id.tabHome, R.id.nav_home}, {R.id.tabSearch, R.id.nav_search},
                {R.id.tabSaved, R.id.nav_saved}, {R.id.tabProgress, R.id.nav_progress},
                {R.id.tabSettings, R.id.nav_settings}};
        for (final int[] tab : tabs) {
            final View t = nav.findViewById(tab[0]);
            t.setSelected(tab[1] == selectedId);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (tab[1] == selectedId) return;
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    Anim.pulse(v);
                    if (tab[1] == R.id.nav_home) navTo(StudentDashboardActivity.class);
                    else if (tab[1] == R.id.nav_search) navTo(SearchActivity.class);
                    else if (tab[1] == R.id.nav_saved) navTo(BookmarksActivity.class);
                    else if (tab[1] == R.id.nav_progress) navTo(ProgressActivity.class);
                    else if (tab[1] == R.id.nav_settings) navTo(SettingsActivity.class);
                }
            });
        }
        nav.findViewById(R.id.btnVivi).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                open(ChatActivity.class);
            }
        });
    }

    /** Keeps a floating control a little above the system gesture bar, whatever its height. */
    private void liftAboveGestureBar(final View v, final FrameLayout.LayoutParams lp) {
        final int base = getResources().getDimensionPixelSize(R.dimen.nav_float_margin) - dp(4);
        lp.bottomMargin = base;
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) view.getLayoutParams();
            if (p.bottomMargin != base + bottom) {
                p.bottomMargin = base + bottom;
                view.setLayoutParams(p);
            }
            return insets;
        });
    }

    /**
     * Every tab is its own Activity, so each one is launched CLEAR_TOP | SINGLE_TOP.
     * Without those flags, hopping between tabs stacks duplicate activities and Back walks
     * back through the entire browsing history instead of leaving the app.
     */
    private void navTo(Class<?> target) {
        Intent i = new Intent(this, target);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        if (Anim.enabled(this)) overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------- views

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Builds one tappable card. Every screen that renders a list outside a RecyclerView
     * (sources, related resources, per-course breakdowns) uses this, so the styling
     * stays identical everywhere.
     */
    protected LinearLayout card(String title, String subtitle, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        t.setTextSize(15);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            s.setTextSize(13);
            s.setPadding(0, dp(3), 0, 0);
            card.addView(s);
        }

        if (onClick != null) {
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(onClick);
        }
        return card;
    }

    // -------------------------------------------------------------- navigation

    protected void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    protected void showError(int viewId, String message) {
        TextView tv = findViewById(viewId);
        if (tv == null) return;
        if (message == null) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setText(message);
            tv.setVisibility(View.VISIBLE);
        }
    }

    /** Clears a stale validation error the moment the user starts fixing the input. */
    protected void clearErrorWhileTyping(final int errorViewId, android.widget.EditText... fields) {
        android.text.TextWatcher w = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                showError(errorViewId, null);
            }
        };
        for (android.widget.EditText f : fields) if (f != null) f.addTextChangedListener(w);
    }

    protected void open(Class<?> target) {
        startActivity(new Intent(this, target));
        applyOpenTransition();
    }

    /**
     * Opens a detail screen with the tapped card's title morphing into the detail header.
     *
     * Falls back to the ordinary slide when animations are off or the caller has no view to
     * share, so navigation never depends on the transition succeeding.
     */
    protected void openShared(Class<?> target, String extraKey, long extraValue, View shared) {
        if (shared == null || !Anim.enabled(this)) {
            open(target, extraKey, extraValue);
            return;
        }
        Intent i = new Intent(this, target);
        i.putExtra(extraKey, extraValue);
        i.putExtra(EXTRA_SHARED, true);

        androidx.core.view.ViewCompat.setTransitionName(shared, SHARED_TITLE);
        startActivity(i, androidx.core.app.ActivityOptionsCompat
                .makeSceneTransitionAnimation(this, shared, SHARED_TITLE).toBundle());
    }

    /** Destination side: names the header so the framework can pair it with the card. */
    protected void receiveSharedTitle(int viewId) {
        View v = findViewById(viewId);
        if (v != null && enteredShared()) {
            androidx.core.view.ViewCompat.setTransitionName(v, SHARED_TITLE);
        }
    }

    protected void open(Class<?> target, String extraKey, long extraValue) {
        Intent i = new Intent(this, target);
        i.putExtra(extraKey, extraValue);
        startActivity(i);
        applyOpenTransition();
    }

    /** Forward navigation: the new screen slides in from the right as this one eases back. */
    protected void applyOpenTransition() {
        if (Anim.enabled(this)) {
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        }
    }

    /** Guards the re-entrant finish() that finishAfterTransition triggers once it completes. */
    private boolean exiting;

    /**
     * Back navigation mirrors the way in. A screen entered through a shared element reverses
     * that morph; every other screen slides back out.
     */
    @Override
    public void finish() {
        if (!exiting && enteredShared()) {
            exiting = true;
            androidx.core.app.ActivityCompat.finishAfterTransition(this);
            return;
        }
        super.finish();
        // Skip the slide when the shared element is already animating the exit itself.
        if (!exiting && Anim.enabled(this)) {
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        }
    }

    /** Sends the user to the dashboard matching their role (SRS FR 1.3). */
    protected void openDashboard(User u) {
        Intent i = new Intent(this, u.isTeacher() ? TeacherDashboardActivity.class
                                                  : StudentDashboardActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
    }

    protected void logout() {
        CloudRepo.signOut(this);   // stop listeners, sign out of Firebase, empty the local mirror
        session.logout();
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
    }
}
