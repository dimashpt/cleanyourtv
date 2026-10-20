package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Edit dock order and apps on the TV; saved in {@link Config#PREF_DOCK_APPS}. */
public final class DockSettingsActivity extends Activity {
    private List<String> slots = new ArrayList<>();
    private PackageManager pm;
    private List<AppChoice> apps = new ArrayList<>();
    private ArrayAdapter<String> slotAdapter;
    private boolean dirty;

    private static final class AppChoice {
        final String pkg;
        final String label;

        AppChoice(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_dock_settings);
        pm = getPackageManager();
        Config cfg = Config.load(this);
        slots = new ArrayList<>(DockOrder.normalize(cfg.dockApps, cfg.columns));
        loadAppChoices();

        ListView list = findViewById(R.id.dockSlots);
        slotAdapter = new ArrayAdapter<>(this, R.layout.item_dock_slot, android.R.id.text1, new ArrayList<>());
        list.setAdapter(slotAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> showPicker(position));
        list.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = list.getSelectedItemPosition();
            if (pos < 0) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos > 0) {
                swap(pos, pos - 1);
                list.setSelection(pos - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos < slots.size() - 1) {
                swap(pos, pos + 1);
                list.setSelection(pos + 1);
                return true;
            }
            return false;
        });
        refreshAdapter();
        list.requestFocus();
        if (!slots.isEmpty()) list.setSelection(0);
    }

    private void loadAppChoices() {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        apps = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            String label = Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
            apps.add(new AppChoice(pkg, label));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
    }

    private String formatSlot(int index) {
        String pkg = slots.get(index);
        String name = labelFor(pkg);
        if (name.isEmpty()) name = getString(R.string.dock_slot_empty);
        return getString(R.string.dock_slot_line, index + 1, name);
    }

    private String labelFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        for (AppChoice c : apps) {
            if (c.pkg.equals(pkg)) return c.label;
        }
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void refreshAdapter() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) lines.add(formatSlot(i));
        slotAdapter.clear();
        slotAdapter.addAll(lines);
        slotAdapter.notifyDataSetChanged();
    }

    private void swap(int a, int b) {
        String tmp = slots.get(a);
        slots.set(a, slots.get(b));
        slots.set(b, tmp);
        dirty = true;
        refreshAdapter();
    }

    private void showPicker(int slotIndex) {
        int n = apps.size();
        CharSequence[] labels = new CharSequence[n + 1];
        for (int i = 0; i < n; i++) labels[i] = apps.get(i).label;
        labels[n] = getString(R.string.dock_clear_slot);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dock_pick_app)
                .setItems(labels, (dialog, which) -> {
                    slots.set(slotIndex, which == n ? "" : apps.get(which).pkg);
                    dirty = true;
                    refreshAdapter();
                })
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dirty) {
            Config.saveUserDockApps(this, slots);
            setResult(RESULT_OK);
        }
    }
}
