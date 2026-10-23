package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (fixed slots, holes allowed),
 * and positions from {@link MainActivity#COLUMNS} onwards are the free-order app grid (newly installed apps
 * appended at the end).
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Long-press on an app tile (not empty dock slots or shortcuts). */
        void onTileLongPress(int position, View card);

        boolean isDockEditActive();
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, DOCK_EMPTY = 3, EMPTY = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = kind == DOCK_EMPTY ? "" : component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
        }

        static Entry emptyDockSlot(Context ctx, int slotId) {
            return new Entry(
                    DOCK_EMPTY,
                    ctx.getString(R.string.dock_slot_empty),
                    new ComponentName(ctx.getPackageName(), "DockEmptySlot_" + slotId),
                    null,
                    null,
                    null
            );
        }
    }

    static final float FOCUS_SCALE = 1.25f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 150f, DESIGN_H_DP = 84f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = cell - Math.round(26 * density);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock slots (size = dockSize, "" = empty slot). */
    private final List<String> favourites;
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock). */
    private final boolean fillDock;
    /** Free-order grid package list (persisted in SharedPreferences). */
    private final List<String> gridOrder;
    private String editCarryPkg;
    private boolean editInDock;
    private int editCursorPosition = -1;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = new ArrayList<>(DockOrder.normalize(cfg.dockApps, dockSize));
        this.fillDock = cfg.fillDock;
        this.gridOrder = new ArrayList<>(cfg.gridApps);
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.reducedMotion = cfg.reducedMotion;
        setHasStableIds(true);
    }

    void setDockSlots(List<String> slots) {
        favourites.clear();
        favourites.addAll(DockOrder.normalize(slots, dockSize));
    }

    List<String> getDockSlots() {
        return new ArrayList<>(favourites);
    }

    void setGridOrder(List<String> order) {
        gridOrder.clear();
        if (order != null) gridOrder.addAll(order);
    }

    List<String> getGridOrder() {
        return new ArrayList<>(gridOrder);
    }

    Set<String> getInstalledAppPkgs() {
        Set<String> pkgs = new HashSet<>();
        for (Entry e : items) {
            if ((e.kind == Entry.APP || e.kind == Entry.CARD) && !e.pkg.isEmpty()) {
                pkgs.add(e.pkg);
            }
        }
        return pkgs;
    }

    /** Last movable position in the grid (excludes the optional Home Settings shortcut at the end). */
    int getEditableGridEnd() {
        int end = items.size() - 1;
        while (end >= dockSize && items.get(end).kind == Entry.HOME_SETTINGS) {
            end--;
        }
        return end;
    }

    void clearEditVisuals() {
        this.editCarryPkg = null;
        this.editInDock = false;
        this.editCursorPosition = -1;
        notifyItemRangeChanged(0, items.size());
    }

    boolean isEditLifted() {
        return editCarryPkg != null;
    }

    void animateEnterEdit(RecyclerView grid, int position, String pkg, boolean inDock) {
        this.editCarryPkg = pkg;
        this.editInDock = inDock;
        this.editCursorPosition = position;
        float density = ctx.getResources().getDisplayMetrics().density;
        float liftScale = !animations ? 1f : (reducedMotion ? 1.12f : 1.18f);

        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(child);
            if (!(vh instanceof Holder)) continue;
            Holder h = (Holder) vh;
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= items.size()) continue;
            Entry e = items.get(pos);
            boolean isLifted = pos == position;

            h.card.animate().cancel();
            h.banner.animate().cancel();
            h.name.animate().cancel();
            h.itemView.animate().cancel();

            if (isLifted) {
                h.itemView.setTranslationZ(24f * density);
                h.card.setBackgroundResource(R.drawable.tile_edit_lift);
                h.showEditIndicators(inDock, density);
                if (!animations) {
                    h.itemView.setAlpha(1f);
                    h.card.setScaleX(liftScale);
                    h.card.setScaleY(liftScale);
                    h.card.setTranslationY(-8f * density);
                    h.card.setTranslationZ(18f * density);
                    h.name.setAlpha(pos < dockSize ? 0f : 1f);
                } else {
                    h.itemView.animate().alpha(1f).setDuration(120).start();
                    h.card.animate()
                            .scaleX(liftScale)
                            .scaleY(liftScale)
                            .translationY(-8f * density)
                            .translationZ(18f * density)
                            .setDuration(reducedMotion ? 110 : 190)
                            .setInterpolator(reducedMotion
                                    ? new DecelerateInterpolator()
                                    : new OvershootInterpolator(1.3f))
                            .withLayer()
                            .start();
                    h.name.animate().alpha(pos < dockSize ? 0f : 1f).setDuration(140).start();
                }
            } else {
                h.hideEditIndicators();
                h.itemView.setTranslationZ(0f);
                float dim = inDock ? (pos < dockSize ? 0.55f : 0.35f) : (pos >= dockSize ? 0.55f : 0.35f);
                if (e.kind == Entry.DOCK_EMPTY) {
                    h.card.setBackgroundResource(inDock ? R.drawable.tile_dock_drop_target : R.drawable.tile_dock_empty);
                } else {
                    h.card.setBackgroundResource(R.drawable.tile_bg);
                }
                if (!animations) {
                    h.itemView.setAlpha(dim);
                    h.card.setScaleX(1f);
                    h.card.setScaleY(1f);
                    h.card.setTranslationY(0f);
                    h.card.setTranslationZ(0f);
                    h.name.setAlpha(0f);
                } else {
                    h.itemView.animate().alpha(dim).setDuration(reducedMotion ? 100 : 160).start();
                    h.card.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .translationZ(0f)
                            .setDuration(reducedMotion ? 100 : 160)
                            .setInterpolator(new DecelerateInterpolator())
                            .withLayer()
                            .start();
                    h.name.animate().alpha(0f).setDuration(120).start();
                }
            }
        }
    }

    void animateExitEdit(RecyclerView grid, int finalPosition) {
        this.editCarryPkg = null;
        this.editInDock = false;
        this.editCursorPosition = -1;
        float density = ctx.getResources().getDisplayMetrics().density;
        float fScale = focusScale();

        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(child);
            if (!(vh instanceof Holder)) continue;
            Holder h = (Holder) vh;
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || pos >= items.size()) continue;
            Entry e = items.get(pos);
            boolean isTarget = pos == finalPosition;

            h.hideEditIndicators();
            h.card.animate().cancel();
            h.banner.animate().cancel();
            h.name.animate().cancel();
            h.itemView.animate().cancel();
            h.itemView.setTranslationZ(0f);

            h.card.setBackgroundResource(e.kind == Entry.DOCK_EMPTY
                    ? R.drawable.tile_dock_empty : R.drawable.tile_bg);
            h.name.setVisibility(pos < dockSize || e.kind == Entry.DOCK_EMPTY ? View.INVISIBLE : View.VISIBLE);

            float targetScale = isTarget ? fScale : 1f;
            float targetY = isTarget && advancedEffects ? -4f * density : 0f;
            float targetZ = isTarget && advancedEffects ? 8f * density : 0f;
            float targetNameAlpha = (isTarget && pos >= dockSize && e.kind != Entry.DOCK_EMPTY) ? 1f : 0f;

            if (!animations) {
                h.itemView.setAlpha(1f);
                h.card.setScaleX(targetScale);
                h.card.setScaleY(targetScale);
                h.card.setTranslationY(targetY);
                h.card.setTranslationZ(targetZ);
                h.name.setAlpha(targetNameAlpha);
            } else {
                h.itemView.animate().alpha(1f).setDuration(reducedMotion ? 100 : 170).start();
                h.card.animate()
                        .scaleX(targetScale)
                        .scaleY(targetScale)
                        .translationY(targetY)
                        .translationZ(targetZ)
                        .setDuration(reducedMotion ? 110 : 190)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .withLayer()
                        .start();
                h.name.animate().alpha(targetNameAlpha).setDuration(150).start();
            }
        }
    }

    /**
     * Moves an item from {@code fromPos} to {@code toPos} during edit mode and notifies RecyclerView
     * so ItemAnimator smoothly translates the lifted tile and all shifted neighbour tiles.
     */
    void moveEditItem(int fromPos, int toPos) {
        if (fromPos == toPos || fromPos < 0 || fromPos >= items.size()
                || toPos < 0 || toPos >= items.size()) {
            return;
        }
        Entry moved = items.remove(fromPos);
        items.add(toPos, moved);
        editCursorPosition = toPos;

        if (fromPos < dockSize && toPos < dockSize) {
            for (int i = 0; i < dockSize && i < items.size(); i++) {
                favourites.set(i, items.get(i).pkg);
            }
        } else if (fromPos >= dockSize && toPos >= dockSize) {
            gridOrder.clear();
            for (int i = dockSize; i < items.size(); i++) {
                Entry e = items.get(i);
                if (e.kind != Entry.HOME_SETTINGS && !e.pkg.isEmpty()) {
                    gridOrder.add(e.pkg);
                }
            }
        }
        notifyItemMoved(fromPos, toPos);
    }

    String labelForPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        for (Entry e : items) {
            if (pkg.equals(e.pkg)) return e.label;
        }
        try {
            return String.valueOf(ctx.getPackageManager().getApplicationLabel(
                    ctx.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    int entryKindAt(int position) {
        if (position < 0 || position >= items.size()) return -1;
        return items.get(position).kind;
    }

    String pkgAt(int position) {
        if (position < 0 || position >= items.size()) return "";
        return items.get(position).pkg;
    }

    void reload(List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);

        List<Entry> allInstalled = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            Drawable banner = loadBanner(pm, ri);
            Drawable icon = ri.loadIcon(pm);
            if (card != null) {
                allInstalled.add(new Entry(Entry.CARD, card.label, component, banner, icon, card.caption));
            } else {
                allInstalled.add(new Entry(Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg), component, banner, icon));
            }
        }

        // Default sort (Cards first, then alphabetical) used for first-time initialization or when multiple new apps are installed at once.
        Collections.sort(allInstalled, (a, b) -> {
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });

        Map<String, Entry> entryByPkg = new LinkedHashMap<>();
        for (Entry e : allInstalled) {
            if (!entryByPkg.containsKey(e.pkg)) {
                entryByPkg.put(e.pkg, e);
            }
        }

        List<String> normalizedDock = DockOrder.normalize(favourites, dockSize);
        favourites.clear();
        favourites.addAll(normalizedDock);

        List<Entry> fresh = new ArrayList<>();
        Set<String> inDock = new HashSet<>();
        int emptySlotCounter = 0;
        for (int i = 0; i < dockSize; i++) {
            String dockPkg = favourites.get(i);
            Entry dockEntry = (!dockPkg.isEmpty()) ? entryByPkg.get(dockPkg) : null;
            if (dockEntry != null && inDock.add(dockPkg)) {
                fresh.add(dockEntry);
            } else {
                fresh.add(Entry.emptyDockSlot(ctx, emptySlotCounter++));
            }
        }

        List<String> availableGridPkgs = new ArrayList<>();
        for (Entry e : allInstalled) {
            if (!inDock.contains(e.pkg) && !availableGridPkgs.contains(e.pkg)) {
                availableGridPkgs.add(e.pkg);
            }
        }

        List<String> reconciledGrid = DockOrder.reconcileGridOrder(gridOrder, availableGridPkgs);
        if (!reconciledGrid.equals(gridOrder)) {
            gridOrder.clear();
            gridOrder.addAll(reconciledGrid);
            Config.saveUserGridApps(ctx, gridOrder);
        }

        for (String pkg : gridOrder) {
            Entry e = entryByPkg.get(pkg);
            if (e != null) {
                fresh.add(e);
            }
        }

        // Retain the optional Default-home tile at the end.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null));
        }

        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener, animations, advancedEffects, reducedMotion, w, h);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        float density = ctx.getResources().getDisplayMetrics().density;
        boolean isEmptyDock = e.kind == Entry.DOCK_EMPTY;

        h.name.setText(isEmptyDock ? "" : e.name);
        h.name.setVisibility(position < dockSize || isEmptyDock ? View.INVISIBLE : View.VISIBLE);

        if (isEmptyDock) {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(null);
            h.icon.setVisibility(View.GONE);
            h.label.setText("");
            h.label.setVisibility(View.GONE);
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }

        boolean editing = editCarryPkg != null;
        if (editing) {
            boolean isLifted = position == editCursorPosition
                    || (!e.pkg.isEmpty() && e.pkg.equals(editCarryPkg)
                    && (editInDock ? position < dockSize : position >= dockSize));
            if (isLifted) {
                float liftScale = !animations ? 1f : (reducedMotion ? 1.12f : 1.18f);
                h.itemView.setAlpha(1f);
                h.itemView.setTranslationZ(24f * density);
                h.card.setBackgroundResource(R.drawable.tile_edit_lift);
                h.card.setScaleX(liftScale);
                h.card.setScaleY(liftScale);
                h.card.setTranslationY(-8f * density);
                h.card.setTranslationZ(18f * density);
                h.name.setAlpha(position < dockSize ? 0f : 1f);
                h.showEditIndicators(editInDock, density);
            } else {
                h.hideEditIndicators();
                float dim = editInDock
                        ? (position < dockSize ? 0.55f : 0.35f)
                        : (position >= dockSize ? 0.55f : 0.35f);
                h.itemView.setAlpha(dim);
                h.itemView.setTranslationZ(0f);
                if (isEmptyDock) {
                    h.card.setBackgroundResource(editInDock ? R.drawable.tile_dock_drop_target : R.drawable.tile_dock_empty);
                } else {
                    h.card.setBackgroundResource(R.drawable.tile_bg);
                }
                h.card.setScaleX(1f);
                h.card.setScaleY(1f);
                h.card.setTranslationY(0f);
                h.card.setTranslationZ(0f);
                h.name.setAlpha(0f);
            }
        } else {
            boolean focused = h.card.isFocused();
            h.itemView.animate().cancel();
            h.itemView.setAlpha(1f);
            h.itemView.setTranslationZ(0f);
            h.hideEditIndicators();
            h.card.setBackgroundResource(isEmptyDock ? R.drawable.tile_dock_empty : R.drawable.tile_bg);
            h.name.setAlpha(focused && !isEmptyDock ? 1f : 0f);
            h.card.setScaleX(focused && animations ? focusScale() : 1f);
            h.card.setScaleY(focused && animations ? focusScale() : 1f);
            h.card.setTranslationY(focused && advancedEffects ? -4f * density : 0f);
            h.card.setTranslationZ(focused && advancedEffects ? 8f * density : 0f);
            h.banner.setScaleX(focused && advancedEffects ? 1.035f : 1f);
            h.banner.setScaleY(focused && advancedEffects ? 1.035f : 1f);
        }

        h.card.setOnClickListener(v -> {
            if (isEditLifted() || e.kind == Entry.DOCK_EMPTY) return;
            launch(e);
        });
        h.card.setOnLongClickListener(v -> {
            if (isEditLifted() || listener == null) return false;
            if (e.kind != Entry.APP && e.kind != Entry.CARD) return false;
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            listener.onTileLongPress(pos, h.card);
            return true;   // suppress the follow-up click from the same OK press
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            if (advancedEffects && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    /** Dock slots that hold an app: in edit mode, all slots can be targeted; otherwise up to the last filled slot. */
    int dockFilled() {
        if (listener != null && listener.isDockEditActive()) return dockSize;
        int lastNonEmpty = -1;
        for (int i = 0; i < Math.min(dockSize, items.size()); i++) {
            if (items.get(i).kind != Entry.DOCK_EMPTY) {
                lastNonEmpty = i;
            }
        }
        return lastNonEmpty >= 0 ? lastNonEmpty + 1 : dockSize;
    }

    /** A position focus can land on: an empty dock slot outside edit mode sends focus to a non-empty slot. */
    int focusablePosition(int position) {
        if (listener != null && listener.isDockEditActive()) return position;
        if (position >= 0 && position < Math.min(dockSize, items.size()) && items.get(position).kind == Entry.DOCK_EMPTY) {
            for (int i = position - 1; i >= 0; i--) {
                if (items.get(i).kind != Entry.DOCK_EMPTY) return i;
            }
            for (int i = position + 1; i < Math.min(dockSize, items.size()); i++) {
                if (items.get(i).kind != Entry.DOCK_EMPTY) return i;
            }
        }
        return position;
    }

    /** Position of the app with this package name, or -1 when not present. */
    int positionOfPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (pkg.equals(items.get(i).pkg)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    float focusScale() {
        return !animations ? 1f : FOCUS_SCALE;
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final View editBorder;
        final ImageView arrowUp;
        final ImageView arrowDown;
        final ImageView arrowLeft;
        final ImageView arrowRight;
        final boolean animations;
        final boolean advancedEffects;
        final boolean reducedMotion;
        final int tileWidth;
        final int tileHeight;
        android.animation.ObjectAnimator borderPulse;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects,
               boolean reducedMotion, int tileWidth, int tileHeight) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            editBorder = v.findViewById(R.id.editBorder);
            arrowUp = v.findViewById(R.id.arrowUp);
            arrowDown = v.findViewById(R.id.arrowDown);
            arrowLeft = v.findViewById(R.id.arrowLeft);
            arrowRight = v.findViewById(R.id.arrowRight);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || listener == null) return false;
                return listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                if (listener != null && listener.isDockEditActive()) return;
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                float scale = !animations ? 1f : FOCUS_SCALE;
                float s = focused ? scale : 1f;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setAlpha(focused ? 1f : 0f);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects ? -4f * density : 0f)
                            .translationZ(focused ? 12f * density : 0f)
                            .setDuration(focused ? 120 : 80)
                            .setInterpolator(new DecelerateInterpolator())
                            .withLayer().start();
                    name.animate().alpha(focused ? 1f : 0f).setDuration(100).start();
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects ? -4f * density : 0f)
                            .translationZ(focused ? 14f * density : 0f)
                            .setDuration(advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                            .setInterpolator(new OvershootInterpolator(focused ? (advancedEffects ? 0.7f : 1.6f) : 0f))
                            .withLayer().start();
                    if (advancedEffects) {
                        banner.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                                .setDuration(280).start();
                    }
                    name.animate().alpha(focused ? 1f : 0f)
                            .translationY(focused ? density * 4f : -density * 6f)
                            .setDuration(220).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }

        void showEditIndicators(boolean inDock, float density) {
            if (editBorder != null) {
                editBorder.setVisibility(View.VISIBLE);
                if (animations) {
                    if (borderPulse == null) {
                        borderPulse = android.animation.ObjectAnimator.ofFloat(editBorder, "alpha", 1.0f, 0.2f);
                        borderPulse.setDuration(700);
                        borderPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                        borderPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                        borderPulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                    }
                    if (!borderPulse.isStarted()) {
                        borderPulse.start();
                    }
                } else {
                    editBorder.setAlpha(1.0f);
                }
            }

            float liftScale = 1.18f;
            float halfW = (tileWidth * liftScale) / 2f;
            float halfH = (tileHeight * liftScale) / 2f;
            float liftY = -8f * density;

            if (!inDock) {
                if (arrowUp != null) {
                    arrowUp.setVisibility(View.VISIBLE);
                    arrowUp.setTranslationY(liftY + (tileHeight / 2f) - halfH - 22f * density);
                }
                if (arrowDown != null) {
                    arrowDown.setVisibility(View.VISIBLE);
                    arrowDown.setTranslationY(liftY + (tileHeight / 2f) + halfH + 4f * density);
                }
            } else {
                if (arrowUp != null) arrowUp.setVisibility(View.GONE);
                if (arrowDown != null) arrowDown.setVisibility(View.GONE);
            }

            if (arrowLeft != null) {
                arrowLeft.setVisibility(View.VISIBLE);
                arrowLeft.setTranslationX(-(halfW + 14f * density));
                arrowLeft.setTranslationY(liftY + (tileHeight / 2f) - 9f * density);
            }
            if (arrowRight != null) {
                arrowRight.setVisibility(View.VISIBLE);
                arrowRight.setTranslationX(halfW + 14f * density);
                arrowRight.setTranslationY(liftY + (tileHeight / 2f) - 9f * density);
            }
        }

        void hideEditIndicators() {
            if (borderPulse != null) {
                borderPulse.cancel();
                borderPulse = null;
            }
            if (editBorder != null) {
                editBorder.setAlpha(1f);
                editBorder.setVisibility(View.GONE);
            }
            if (arrowUp != null) arrowUp.setVisibility(View.GONE);
            if (arrowDown != null) arrowDown.setVisibility(View.GONE);
            if (arrowLeft != null) arrowLeft.setVisibility(View.GONE);
            if (arrowRight != null) arrowRight.setVisibility(View.GONE);
        }
    }
}
