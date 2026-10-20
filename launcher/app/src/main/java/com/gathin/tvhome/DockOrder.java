package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fixed-position dock slots (empty string = hole) and free-order grid helpers. */
final class DockOrder {
    private DockOrder() {}

    static List<String> normalize(List<String> dock, int columns) {
        int n = Math.max(1, columns);
        List<String> out = new ArrayList<>(n);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String pkg = (dock != null && i < dock.size() && dock.get(i) != null)
                    ? dock.get(i).trim() : "";
            if (!pkg.isEmpty() && !seen.add(pkg)) {
                pkg = "";
            }
            out.add(pkg);
        }
        return out;
    }

    static int indexOf(List<String> dock, String pkg) {
        if (dock == null || pkg == null || pkg.isEmpty()) return -1;
        for (int i = 0; i < dock.size(); i++) {
            if (pkg.equals(dock.get(i))) return i;
        }
        return -1;
    }

    /** Negative if {@code a} sorts before {@code b}; used after dock apps, before cards and alpha. */
    static int compareDock(String pkgA, String pkgB, List<String> dock) {
        int da = indexOf(dock, pkgA), db = indexOf(dock, pkgB);
        if (da >= 0 || db >= 0) {
            if (da < 0) return 1;
            if (db < 0) return -1;
            return Integer.compare(da, db);
        }
        return 0;
    }

    /** Index of the first empty (or uninstalled) dock slot, or -1 when all slots are filled. */
    static int firstEmptySlot(List<String> slots, Set<String> installedPkgs) {
        for (int i = 0; i < slots.size(); i++) {
            String pkg = slots.get(i);
            if (pkg == null || pkg.isEmpty() || (installedPkgs != null && !installedPkgs.contains(pkg))) {
                return i;
            }
        }
        return -1;
    }

    /** Promote a grid app into a dock slot; removes the same package from other slots. */
    static List<String> applyGridToDock(List<String> slots, int targetSlot, String pkg) {
        List<String> out = new ArrayList<>(slots);
        for (int i = 0; i < out.size(); i++) {
            if (i != targetSlot && pkg.equals(out.get(i))) out.set(i, "");
        }
        out.set(targetSlot, pkg);
        return out;
    }

    /** Move/swap a dock slot from {@code sourceSlot} to {@code targetSlot}. */
    static List<String> applyDockMove(List<String> slots, int sourceSlot, int targetSlot) {
        List<String> out = new ArrayList<>(slots);
        if (sourceSlot == targetSlot || sourceSlot < 0 || sourceSlot >= out.size()
                || targetSlot < 0 || targetSlot >= out.size()) {
            return out;
        }
        String moved = out.remove(sourceSlot);
        out.add(targetSlot, moved);
        return out;
    }

    static List<String> applyDockClear(List<String> slots, int slot) {
        List<String> out = new ArrayList<>(slots);
        if (slot >= 0 && slot < out.size()) {
            out.set(slot, "");
        }
        return out;
    }

    /**
     * Reconciles persisted grid order with currently installed non-dock apps:
     * preserves existing order, drops uninstalled/docked apps, and appends newly installed apps at the end.
     */
    static List<String> reconcileGridOrder(List<String> savedOrder, List<String> availablePkgs) {
        if (availablePkgs == null || availablePkgs.isEmpty()) return new ArrayList<>();
        if (savedOrder == null || savedOrder.isEmpty()) return new ArrayList<>(availablePkgs);
        Set<String> availableSet = new HashSet<>(availablePkgs);
        Set<String> added = new HashSet<>();
        List<String> out = new ArrayList<>(availablePkgs.size());
        for (String pkg : savedOrder) {
            if (pkg != null && availableSet.contains(pkg) && added.add(pkg)) {
                out.add(pkg);
            }
        }
        for (String pkg : availablePkgs) {
            if (pkg != null && added.add(pkg)) {
                out.add(pkg);
            }
        }
        return out;
    }

    /** Places {@code pkg} at the very beginning of the grid order (e.g. when removed from dock). */
    static List<String> prependToGridOrder(List<String> gridOrder, String pkg) {
        List<String> out = new ArrayList<>();
        if (pkg != null && !pkg.isEmpty()) {
            out.add(pkg);
        }
        if (gridOrder != null) {
            for (String existing : gridOrder) {
                if (existing != null && !existing.isEmpty() && !existing.equals(pkg)) {
                    out.add(existing);
                }
            }
        }
        return out;
    }
}
