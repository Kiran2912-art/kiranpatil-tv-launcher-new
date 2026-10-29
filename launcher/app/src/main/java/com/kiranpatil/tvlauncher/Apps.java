package com.kiranpatil.tvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the dock and grid app lists. Disabled packages simply don't appear. */
final class Apps {
    private Apps() {}

    static final class Entry {
        final String pkg;
        final String label;
        final ComponentName component;
        final Drawable banner; // 16:9 TV banner, may be null
        final Drawable icon;
        int glow; // sampled only for dock apps

        Entry(String pkg, String label, ComponentName component, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.banner = banner;
            this.icon = icon;
        }

        Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    static final class Result {
        final List<Entry> dock = new ArrayList<>();
        final List<Entry> grid = new ArrayList<>();
    }

    static Result load(Context c, Config cfg) {
        PackageManager pm = c.getPackageManager();
        Map<String, Entry> all = new LinkedHashMap<>();
        collect(c, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, all);
        if (cfg.showPhoneApps) collect(c, pm, Intent.CATEGORY_LAUNCHER, all);

        Result r = new Result();
        for (List<String> slot : cfg.dock) {
            for (String pkg : slot) {
                Entry e = all.remove(pkg);
                if (e != null) {
                    e.glow = Ui.sampleColor(e.banner != null ? e.banner : e.icon);
                    r.dock.add(e);
                    // Other candidates for the same slot stay in the grid.
                    break;
                }
            }
        }
        for (Entry e : all.values()) {
            if (!cfg.hidden.contains(e.pkg)) r.grid.add(e);
        }
        final Collator col = Collator.getInstance();
        Collections.sort(r.grid, (a, b) -> col.compare(a.label, b.label));
        return r;
    }

    private static void collect(Context c, PackageManager pm, String category, Map<String, Entry> out) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || out.containsKey(pkg)) continue;
            Drawable banner = null;
            Drawable icon = null;
            try {
                banner = ri.activityInfo.loadBanner(pm); // falls back to the app's banner
            } catch (Exception ignored) { }
            try {
                icon = ri.loadIcon(pm);
            } catch (Exception ignored) { }
            CharSequence label = ri.loadLabel(pm);
            out.put(pkg, new Entry(pkg, label != null ? label.toString() : pkg,
                    new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
    }
}
