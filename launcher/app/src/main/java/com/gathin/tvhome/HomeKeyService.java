package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Handles remote shortcuts:
 * - Home button on remotes that emit KEYCODE_ALL_APPS (Xiaomi sticks/boxes)
 * - Settings / Dashboard button (KEYCODE_SETTINGS, KEYCODE_NOTIFICATION, KEYCODE_PROFILE_SWITCH) -> opens Quick Settings panel or full Settings
 * - Live TV button (KEYCODE_GUIDE, KEYCODE_TV, KEYCODE_TV_INPUT) -> opens Live TV app (com.tcl.tv)
 */
public class HomeKeyService extends AccessibilityService {

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    private String liveTvPkg;
    private String liveTvAction;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        liveTvPkg = config.liveTvPackage;
        liveTvAction = config.liveTvAction;
    }

    private boolean isSettingsKey(KeyEvent event) {
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_SETTINGS
                || kc == KeyEvent.KEYCODE_NOTIFICATION
                || kc == 288 /* KEYCODE_PROFILE_SWITCH */
                || kc == KeyEvent.KEYCODE_MENU) {
            return true;
        }
        int sc = event.getScanCode();
        if (sc == 204 /* TCL_DASHBOARD */ || sc == 456 /* TCL_QUICK_MENU */) return true;
        for (int code : settingsScancodes) if (sc == code) return true;
        for (int code : settingsKeycodes) if (kc == code) return true;
        return false;
    }

    private boolean isTvKey(KeyEvent event) {
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_GUIDE
                || kc == KeyEvent.KEYCODE_TV
                || kc == KeyEvent.KEYCODE_TV_INPUT
                || kc == KeyEvent.KEYCODE_LAST_CHANNEL
                || kc == KeyEvent.KEYCODE_PROG_RED
                || kc == 170
                || kc == 172
                || kc == 178) {
            return true;
        }
        int sc = event.getScanCode();
        return sc == 471 /* TV_INPUT */ || sc == 595 /* TCL_TV_ON */ || sc == 650 /* TCL_TVPLUS */ || sc == 365 /* TCL_EPG */;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : new Intent(Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) {
                }
            }
            return true;
        }

        if (isTvKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                openLiveTv();
            }
            return true;
        }

        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                startActivity(home);
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    private void openLiveTv() {
        Intent intent = null;
        if (liveTvPkg != null && !liveTvPkg.isEmpty()) {
            intent = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
            if (intent == null && liveTvAction != null && !liveTvAction.isEmpty()) {
                Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
                if (getPackageManager().resolveActivity(byAction, 0) != null) intent = byAction;
            }
        }
        if (intent == null) {
            intent = getPackageManager().getLeanbackLaunchIntentForPackage("com.tcl.tv");
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.tcl.tv", "com.tcl.player.TVActivity"));
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
