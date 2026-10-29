package com.kiranpatil.tvlauncher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Launcher settings. Read from the app's external files folder first
 * (/sdcard/Android/data/com.kiranpatil.tvlauncher/files/config.json, pushable over ADB
 * without a rebuild), then from the bundled assets/config.json.
 */
final class Config {
    int columns = 5;
    final List<List<String>> dock = new ArrayList<>();
    final Set<String> hidden = new HashSet<>();
    String greetingName = "";
    String liveTvPackage = "";
    final Map<String, String> inputLabels = new HashMap<>();
    boolean showPhoneApps = false;
    float wallpaperDim = 0.55f;

    /** Non-null when the override file existed but could not be parsed. */
    String overrideError;

    static Config load(Context c) {
        Config cfg = new Config();
        File dir = c.getExternalFilesDir(null);
        File override = dir != null ? new File(dir, "config.json") : null;
        if (override != null && override.isFile()) {
            try (InputStream in = new FileInputStream(override)) {
                cfg.parse(read(in));
                return cfg;
            } catch (Exception e) {
                cfg = new Config();
                cfg.overrideError = "config.json on the TV is invalid, using the built-in one";
            }
        }
        try (InputStream in = c.getAssets().open("config.json")) {
            cfg.parse(read(in));
        } catch (Exception ignored) {
            // Defaults above still give a working launcher.
        }
        return cfg;
    }

    private void parse(String text) throws Exception {
        JSONObject o = new JSONObject(text);
        columns = Math.max(3, Math.min(7, o.optInt("columns", columns)));
        JSONArray d = o.optJSONArray("dock_apps");
        if (d != null) {
            dock.clear();
            for (int i = 0; i < d.length(); i++) {
                List<String> slot = new ArrayList<>();
                Object v = d.get(i);
                if (v instanceof JSONArray) {
                    JSONArray a = (JSONArray) v;
                    for (int j = 0; j < a.length(); j++) slot.add(a.getString(j));
                } else {
                    slot.add(String.valueOf(v));
                }
                dock.add(slot);
            }
        }
        JSONArray h = o.optJSONArray("hidden_apps");
        if (h != null) for (int i = 0; i < h.length(); i++) hidden.add(h.getString(i));
        greetingName = o.optString("greeting_name", greetingName).trim();
        liveTvPackage = o.optString("live_tv_package", liveTvPackage).trim();
        showPhoneApps = o.optBoolean("show_phone_apps", showPhoneApps);
        wallpaperDim = (float) Math.max(0, Math.min(1, o.optDouble("wallpaper_dim", wallpaperDim)));
        JSONObject labels = o.optJSONObject("input_labels");
        if (labels != null) {
            Iterator<String> it = labels.keys();
            while (it.hasNext()) {
                String k = it.next();
                inputLabels.put(k, labels.getString(k));
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
