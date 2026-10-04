package com.seu.studyassistant.ui;

import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import androidx.core.os.LocaleListCompat;

import com.seu.studyassistant.R;
import com.seu.studyassistant.data.SessionManager;
import com.seu.studyassistant.model.User;

/**
 * Account, preferences, support and sign-out.
 *
 * Built from the app's own card components rather than PreferenceFragmentCompat, so it
 * inherits the same surfaces, chips and spacing as every other screen instead of dropping
 * a differently-styled preference list into the middle of the app.
 */
public class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setupHeader(getString(R.string.settings), true);
        setupBottomNav(R.id.nav_settings);

        buildAccountGroup();
        buildSupportGroup();
        bindTheme();
        bindNotifications();

        MaterialButton logout = findViewById(R.id.btnLogout);
        logout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmLogout(); }
        });

        bindLanguage();

        Anim.heroIn(findViewById(R.id.tvName));
    }

    @Override
    protected void onResume() {
        super.onResume();
        User u = currentUser();
        if (u == null) { logout(); return; }
        ((TextView) findViewById(R.id.tvName)).setText(u.name);
        ((TextView) findViewById(R.id.tvEmail)).setText(u.email);
        ((TextView) findViewById(R.id.tvRole)).setText(
                u.isTeacher() ? getString(R.string.teacher) : getString(R.string.student));
    }

    // ------------------------------------------------------------------- groups

    private void buildAccountGroup() {
        LinearLayout group = findViewById(R.id.groupAccount);
        group.removeAllViews();

        User u = currentUser();
        group.addView(card(getString(R.string.profile),
                u == null ? "" : u.email + "  •  " + u.contact, null));

        group.addView(card(getString(R.string.change_password), null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { changePassword(); }
                }));
    }

    /**
     * Change password: current password first, because Firebase only allows it after a recent
     * sign-in. A Google-only account has no password here, so it is told where to manage one.
     */
    private void changePassword() {
        if (!com.seu.studyassistant.data.CloudRepo.hasPassword(this)) {
            info(getString(R.string.change_password), getString(R.string.password_google_only));
            return;
        }
        int pad = getResources().getDimensionPixelSize(R.dimen.space_xl);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText current = passwordField(R.string.current_password);
        final android.widget.EditText next = passwordField(R.string.new_password);
        final android.widget.EditText confirm = passwordField(R.string.confirm_password);
        box.addView(current);
        box.addView(next);
        box.addView(confirm);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.change_password)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save_changes, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = next.getText().toString();
            if (n.length() < 8) { next.setError(getString(R.string.err_password_short)); return; }
            if (!n.equals(confirm.getText().toString())) { confirm.setError(getString(R.string.err_password_mismatch)); return; }
            v.setEnabled(false);
            com.seu.studyassistant.data.CloudRepo.changePassword(this, current.getText().toString(), n,
                    new com.seu.studyassistant.data.Callback<Void>() {
                        @Override public void onSuccess(Void x) {
                            dialog.dismiss();
                            toast(getString(R.string.password_changed));
                        }
                        @Override public void onError(Exception e) {
                            v.setEnabled(true);
                            int res = com.seu.studyassistant.data.CloudRepo.authErrorRes(e);
                            if (res == R.string.err_bad_credentials) current.setError(getString(R.string.err_current_password));
                            else next.setError(getString(res));
                        }
                    });
        }));
        dialog.show();
    }

    private android.widget.EditText passwordField(int hint) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setHint(hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    /**
     * The language picker.
     *
     * Two things are needed for this to stick, and missing either is what makes a language
     * switch look broken: the choice has to be PERSISTED (BaseActivity re-applies it on every
     * cold start, because below Android 13 the platform does not remember a per-app locale),
     * and the current screen has to be RECREATED so already-inflated text is re-read from the
     * new resources instead of waiting for the next launch.
     *
     * setApplicationLocales already recreates the activity on Android 13+; recreate() is
     * called explicitly for older versions, where it does not.
     */
    private void bindLanguage() {
        final RadioGroup rg = findViewById(R.id.rgLanguage);
        if (rg == null) return;

        String current = session.language();
        if ("en".equals(current)) rg.check(R.id.rbLangEnglish);
        else if ("bn".equals(current)) rg.check(R.id.rbLangBangla);
        else rg.check(R.id.rbLangSystem);

        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                String tag = id == R.id.rbLangEnglish ? "en"
                        : id == R.id.rbLangBangla ? "bn"
                        : SessionManager.LANG_SYSTEM;

                // Guard: check() during binding fires this listener too, and recreating on
                // no change would loop the activity.
                if (tag.equals(session.language())) return;

                session.setLanguage(tag);

                LocaleListCompat locales = SessionManager.LANG_SYSTEM.equals(tag)
                        ? LocaleListCompat.getEmptyLocaleList()
                        : LocaleListCompat.forLanguageTags(tag);
                AppCompatDelegate.setApplicationLocales(locales);

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recreate();
            }
        });
    }

    private void buildSupportGroup() {
        LinearLayout group = findViewById(R.id.groupSupport);
        group.removeAllViews();

        group.addView(card(getString(R.string.help), null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                info(getString(R.string.help), getString(R.string.help_body));
            }
        }));
        group.addView(card(getString(R.string.about), null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                info(getString(R.string.about), getString(R.string.about_body));
            }
        }));
        group.addView(card(getString(R.string.privacy), null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                info(getString(R.string.privacy), getString(R.string.privacy_body));
            }
        }));
    }

    private void info(String title, String body) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    // --------------------------------------------------------------- preferences

    private void bindTheme() {
        RadioGroup rg = findViewById(R.id.rgTheme);

        int mode = session.themeMode();
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) rg.check(R.id.rbLight);
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES) rg.check(R.id.rbDark);
        else rg.check(R.id.rbSystem);

        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                int newMode = id == R.id.rbLight ? AppCompatDelegate.MODE_NIGHT_NO
                        : id == R.id.rbDark ? AppCompatDelegate.MODE_NIGHT_YES
                        : SessionManager.MODE_FOLLOW_SYSTEM;
                if (newMode == session.themeMode()) return;
                session.setThemeMode(newMode);
                AppCompatDelegate.setDefaultNightMode(newMode);
            }
        });
    }

    private void bindNotifications() {
        SwitchMaterial sw = findViewById(R.id.swNotifications);
        sw.setChecked(session.notificationsEnabled());
        sw.setOnCheckedChangeListener((button, checked) -> session.setNotificationsEnabled(checked));
    }

    // ------------------------------------------------------------------- session

    /**
     * Signing out is destructive and one tap away, so it states the consequence and waits
     * for a deliberate confirmation rather than dropping the session immediately.
     */
    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.logout, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        logout();   // clears the session and the whole back stack
                    }
                })
                .show();
    }
}
