package com.kiranpatil.tvlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // ---- layout constants (dp) ----
    private static final int SIDE = 64;
    private static final int BAND_PAD = 22;
    private static final int GAP = 26;
    private static final int TOP_INSET_WHEN_SCROLLED = 64;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private Config cfg;
    private int screenW, screenH, tileW, tileH;

    // views
    private FrameLayout root;
    private Stage stage;
    private FrameLayout scroller;
    private LinearLayout content;
    private HighlightView highlight;
    private LinearLayout topBar;
    private TextView greeting, clock, date, emptyText;
    private TextView heroTitle;
    private View heroGlow;
    private final List<TextView> pills = new ArrayList<>();
    private TextView wifiPill;
    private LinearLayout dropdown;
    private final List<TextView> dropdownItems = new ArrayList<>();

    // focus model
    private final List<TileView> dock = new ArrayList<>();
    private final List<List<TileView>> grid = new ArrayList<>();
    private final List<View> gridRows = new ArrayList<>();
    private int lastDockCol = 0, lastPill = 0;
    private TileView.Pos lastPos = new TileView.Pos(TileView.Pos.DOCK, 0, 0);
    private float scrollTarget = 0;
    private String heroPkg;

    private ConnectivityManager.NetworkCallback netCallback;
    private boolean receiversOn;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        cfg = Config.load(this);
        measureScreen();
        restoreFocusMemory();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        addWallpaper();
        buildStage();
        buildTopBar();
        buildDropdown();
        setContentView(root);

        registerReceivers();
        updateClock();
        updateNetwork();
        loadApps();

        if (cfg.overrideError != null) toast(cfg.overrideError);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        updateNetwork();
        if (getCurrentFocus() == null) main.post(this::restoreFocus);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveFocusMemory();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close menus and go back to the dock.
        closeDropdown();
        if (!dock.isEmpty()) focusDock(lastDockCol);
        else if (!grid.isEmpty()) focusGrid(0, 0);
    }

    @Override
    protected void onDestroy() {
        unregisterReceivers();
        loader.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ geometry

    private void measureScreen() {
        Point p = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(p);
        screenW = Math.max(p.x, p.y);
        screenH = Math.min(p.x, p.y);
        int cols = cfg.columns;
        int avail = screenW - 2 * Ui.dp(SIDE) - 2 * Ui.dp(BAND_PAD) - (cols - 1) * Ui.dp(GAP);
        tileW = avail / cols;
        tileH = Math.round(tileW * 9f / 16f);
    }

    /** Height of a tile including the name line under it. */
    private int tileBlockH() {
        return tileH + Ui.dp(14) + Ui.dp(22);
    }

    // ------------------------------------------------------------------ building

    private void addWallpaper() {
        Bitmap bmp = loadWallpaper();
        if (bmp == null) return; // pitch black by default
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(bmp);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(cfg.wallpaperDim);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }

    private Bitmap loadWallpaper() {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        File dir = getExternalFilesDir(null);
        for (String name : new String[]{"wallpaper.jpg", "wallpaper.png"}) {
            File f = dir != null ? new File(dir, name) : null;
            if (f != null && f.isFile()) {
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), bounds);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
                o.inPreferredConfig = Bitmap.Config.RGB_565; // half the RAM, fine for a dimmed photo
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b != null) return b;
            }
        }
        try (InputStream in = getAssets().open("wallpaper.jpg")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    private int sampleSize(int w, int h) {
        int s = 1;
        while (w / (s * 2) >= screenW && h / (s * 2) >= screenH) s *= 2;
        return s;
    }

    private void buildStage() {
        stage = new Stage(this);
        scroller = new FrameLayout(this);
        scroller.setClipChildren(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroller.addView(content, new FrameLayout.LayoutParams(-1, -2));
        highlight = new HighlightView(this);
        scroller.addView(highlight, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(scroller, new FrameLayout.LayoutParams(-1, -2));
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildTopBar() {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(Ui.dp(SIDE), Ui.dp(40), Ui.dp(SIDE), 0);
        topBar.setClipChildren(false);
        topBar.setClipToPadding(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(18, Ui.regular, 0xB3FFFFFF);
        clock = text(68, Ui.thin, Color.WHITE);
        clock.setIncludeFontPadding(false);
        date = text(18, Ui.light, 0xB3FFFFFF);
        left.addView(greeting);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = Ui.dp(2);
        left.addView(clock, cl);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = Ui.dp(4);
        left.addView(date, dl);
        topBar.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setClipChildren(false);
        right.setGravity(Gravity.TOP | Gravity.END);
        TextView inputs = pill("Inputs", 0, v -> toggleDropdown());
        wifiPill = pill("Wi‑Fi", 1, v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        TextView settings = pill("Settings", 2, v -> openSettings(Settings.ACTION_SETTINGS));
        right.addView(inputs);
        right.addView(wifiPill);
        right.addView(settings);
        topBar.addView(right, new LinearLayout.LayoutParams(-2, -2));

        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    private TextView pill(String label, int index, View.OnClickListener click) {
        TextView t = text(16, Ui.medium, Color.WHITE);
        t.setText(label);
        t.setSingleLine(true);
        t.setMaxWidth(Ui.dp(260));
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(20), Ui.dp(10), Ui.dp(20), Ui.dp(11));
        t.setBackground(pillBg(false));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setTag(new TileView.Pos(TileView.Pos.TOP, 0, index));
        try { t.setDefaultFocusHighlightEnabled(false); } catch (Throwable ignored) { }
        t.setOnFocusChangeListener((v, has) -> {
            TextView p = (TextView) v;
            p.setBackground(pillBg(has));
            p.setTextColor(has ? Color.BLACK : Color.WHITE);
            p.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
            if (has) {
                lastPill = index;
                lastPos = (TileView.Pos) v.getTag();
                highlight.show(false);
                scrollTo(0);
                topBar.animate().alpha(1f).setDuration(200).start();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(12);
        t.setLayoutParams(lp);
        pills.add(t);
        return t;
    }

    private GradientDrawable pillBg(boolean focused) {
        return focused
                ? Ui.round(0xF2FFFFFF, 22)
                : Ui.round(0x2EFFFFFF, 22, 0x26FFFFFF, 1); // frosted look without any blur
    }

    private TextView text(float sp, android.graphics.Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        return t;
    }

    private void buildDropdown() {
        dropdown = new LinearLayout(this);
        dropdown.setOrientation(LinearLayout.VERTICAL);
        dropdown.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        dropdown.setBackground(Ui.round(0xF01C1C1E, 18, 0x33FFFFFF, 1));
        dropdown.setElevation(Ui.dp(12));
        dropdown.setVisibility(View.GONE);
        root.addView(dropdown, new FrameLayout.LayoutParams(Ui.dp(300), -2));
    }

    // ------------------------------------------------------------------ apps

    private void loadApps() {
        loader.execute(() -> {
            final Apps.Result r = Apps.load(getApplicationContext(), cfg);
            runOnUiThread(() -> buildApps(r));
        });
    }

    private final Runnable reloadApps = this::loadApps;

    private void buildApps(Apps.Result r) {
        if (isFinishing()) return;
        TileView.Pos keep = lastPos;
        content.removeAllViews();
        dock.clear();
        grid.clear();
        gridRows.clear();
        int cols = cfg.columns;
        int left = Ui.dp(SIDE) + Ui.dp(BAND_PAD);

        // Hero: focused dock app's name with an ambient glow, sitting just above the dock band.
        int bandH = Ui.dp(BAND_PAD) + tileBlockH() + Ui.dp(8);
        int heroH = Math.max(Ui.dp(120), screenH - Ui.dp(36) - bandH);
        FrameLayout hero = new FrameLayout(this);
        hero.setClipChildren(false);
        heroGlow = new View(this);
        heroGlow.setAlpha(0f);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(Ui.dp(900), Ui.dp(520), Gravity.BOTTOM | Gravity.START);
        gl.leftMargin = left - Ui.dp(380);
        gl.bottomMargin = -Ui.dp(200);
        hero.addView(heroGlow, gl);
        heroTitle = text(54, Ui.light, Color.WHITE);
        heroTitle.setSingleLine(true);
        heroTitle.setIncludeFontPadding(false);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        hl.leftMargin = left;
        hl.bottomMargin = Ui.dp(22);
        hero.addView(heroTitle, hl);
        content.addView(hero, new LinearLayout.LayoutParams(-1, heroH));
        heroPkg = null;

        // Dock band.
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.HORIZONTAL);
        band.setClipChildren(false);
        band.setClipToPadding(false);
        band.setPadding(Ui.dp(BAND_PAD), Ui.dp(BAND_PAD), Ui.dp(BAND_PAD), Ui.dp(8));
        band.setBackground(Ui.round(0xFF121214, 28, 0x14FFFFFF, 1));
        band.setMinimumHeight(bandH);
        for (int i = 0; i < r.dock.size() && i < cols; i++) {
            TileView t = tile(r.dock.get(i), TileView.Pos.DOCK, 0, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = Ui.dp(GAP);
            band.addView(t, lp);
            dock.add(t);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.leftMargin = Ui.dp(SIDE);
        bl.rightMargin = Ui.dp(SIDE);
        content.addView(band, bl);

        // Grid of everything else, same column count as the dock.
        List<Apps.Entry> rest = new ArrayList<>(r.grid);
        for (int i = cols; i < r.dock.size(); i++) rest.add(0, r.dock.get(i)); // more dock apps than columns
        for (int row = 0; row * cols < rest.size(); row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            List<TileView> rowTiles = new ArrayList<>();
            for (int c = 0; c < cols && row * cols + c < rest.size(); c++) {
                TileView t = tile(rest.get(row * cols + c), TileView.Pos.GRID, row, c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (c > 0) lp.leftMargin = Ui.dp(GAP);
                line.addView(t, lp);
                rowTiles.add(t);
            }
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
            ll.leftMargin = left;
            ll.topMargin = row == 0 ? Ui.dp(64) : Ui.dp(GAP);
            content.addView(line, ll);
            grid.add(rowTiles);
            gridRows.add(line);
        }

        // Room to scroll the last row up to the top.
        View tail = new View(this);
        content.addView(tail, new LinearLayout.LayoutParams(1, screenH));

        if (emptyText != null) root.removeView(emptyText);
        emptyText = null;
        if (dock.isEmpty() && grid.isEmpty()) {
            emptyText = text(22, Ui.light, 0xB3FFFFFF);
            emptyText.setText("No TV apps found");
            root.addView(emptyText, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }

        lastPos = keep;
        scroller.post(this::restoreFocus);
    }

    private TileView tile(Apps.Entry app, int section, int row, int col) {
        TileView t = new TileView(this, app, tileW, tileH);
        t.setTag(new TileView.Pos(section, row, col));
        t.setOnClickListener(v -> launch(app));
        t.setOnLongClickListener(v -> {
            // Long-press OK: the app's system info page (force stop, disable, clear data).
            openSettingsIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.pkg, null)));
            return true;
        });
        t.setOnFocusChangeListener((v, has) -> onTileFocus((TileView) v, has));
        return t;
    }

    // ------------------------------------------------------------------ focus

    private void onTileFocus(TileView t, boolean has) {
        t.showFocus(has);
        if (!has) return;
        TileView.Pos p = (TileView.Pos) t.getTag();
        lastPos = p;
        Rect r = new Rect(0, 0, t.cardW, t.cardH);
        scroller.offsetDescendantRectToMyCoords(t.card, r);
        float dx = t.cardW * (TileView.FOCUS_SCALE - 1f) / 2f + Ui.dp(3);
        float dy = t.cardH * (TileView.FOCUS_SCALE - 1f) / 2f + Ui.dp(3);
        highlight.moveTo(new RectF(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy), highlight.getAlpha() > 0.5f);
        highlight.show(true);

        if (p.section == TileView.Pos.DOCK) {
            lastDockCol = p.col;
            scrollTo(0);
            topBar.animate().alpha(1f).setDuration(200).start();
            setHero(t.app);
        } else {
            View line = gridRows.get(p.row);
            scrollTo(-(line.getTop() - Ui.dp(TOP_INSET_WHEN_SCROLLED)));
            topBar.animate().alpha(0f).setDuration(200).start();
        }
    }

    private void setHero(Apps.Entry app) {
        if (app.pkg.equals(heroPkg)) return;
        heroPkg = app.pkg;
        final int color = app.glow;
        heroTitle.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            heroTitle.setText(app.label);
            heroTitle.setShadowLayer(Ui.dp(22), 0, 0, Ui.withAlpha(color, 0x8C));
            heroTitle.animate().alpha(1f).setDuration(180).start();
        }).start();
        heroGlow.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            GradientDrawable g = new GradientDrawable();
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setColors(new int[]{Ui.withAlpha(color, 0x59), Ui.withAlpha(color, 0x1A), Ui.withAlpha(color, 0)});
            g.setGradientRadius(Ui.dp(440));
            g.setGradientCenter(0.5f, 0.5f);
            heroGlow.setBackground(g);
            heroGlow.setScaleY(0.42f); // stretch the circle into a low, wide glow
            heroGlow.animate().alpha(1f).setDuration(260).start();
        }).start();
    }

    private void scrollTo(float y) {
        if (y > 0) y = 0;
        if (y == scrollTarget) return;
        scrollTarget = y;
        scroller.animate().translationY(y).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            // Home has nowhere to go back to; Back only closes the Inputs menu.
            if (!down && dropdown.getVisibility() == View.VISIBLE) closeDropdown();
            return true;
        }
        if (!isDpad(k)) return super.dispatchKeyEvent(e);
        if (!down) return true;

        if (dropdown.getVisibility() == View.VISIBLE) {
            moveInDropdown(k);
            return true;
        }
        moveFocus(k);
        return true;
    }

    private static boolean isDpad(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** Explicit, predictable moves: Up/Down exactly one row, ends never fall through. */
    private void moveFocus(int k) {
        View f = getCurrentFocus();
        TileView.Pos p = f != null && f.getTag() instanceof TileView.Pos ? (TileView.Pos) f.getTag() : null;
        if (p == null) {
            restoreFocus();
            return;
        }
        switch (p.section) {
            case TileView.Pos.TOP:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) focusPill(p.col - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) focusPill(p.col + 1);
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!dock.isEmpty()) focusDock(lastDockCol);
                    else if (!grid.isEmpty()) focusGrid(0, 0);
                }
                break;
            case TileView.Pos.DOCK:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) focusDock(p.col - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) focusDock(p.col + 1);
                else if (k == KeyEvent.KEYCODE_DPAD_UP) focusPill(lastPill);
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN && !grid.isEmpty()) focusGrid(0, p.col);
                break;
            case TileView.Pos.GRID:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) focusGrid(p.row, p.col - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) focusGrid(p.row, p.col + 1);
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN && p.row + 1 < grid.size()) focusGrid(p.row + 1, p.col);
                else if (k == KeyEvent.KEYCODE_DPAD_UP) {
                    if (p.row > 0) focusGrid(p.row - 1, p.col);
                    else if (!dock.isEmpty()) focusDock(p.col);
                    else focusPill(lastPill);
                }
                break;
        }
    }

    private void focusPill(int i) {
        if (pills.isEmpty()) return;
        pills.get(clamp(i, pills.size())).requestFocus();
    }

    private void focusDock(int i) {
        if (dock.isEmpty()) return;
        dock.get(clamp(i, dock.size())).requestFocus();
    }

    private void focusGrid(int row, int col) {
        if (grid.isEmpty()) return;
        List<TileView> r = grid.get(clamp(row, grid.size()));
        r.get(clamp(col, r.size())).requestFocus();
    }

    private static int clamp(int i, int size) {
        return Math.max(0, Math.min(size - 1, i));
    }

    /** Back to the last focused place, e.g. after returning from an app or a rebuild. */
    private void restoreFocus() {
        TileView.Pos p = lastPos;
        if (p.section == TileView.Pos.GRID && !grid.isEmpty()) focusGrid(p.row, p.col);
        else if (p.section == TileView.Pos.TOP) focusPill(p.col);
        else if (!dock.isEmpty()) focusDock(p.section == TileView.Pos.DOCK ? p.col : lastDockCol);
        else if (!grid.isEmpty()) focusGrid(0, 0);
        else focusPill(0);
    }

    private void saveFocusMemory() {
        getPreferences(MODE_PRIVATE).edit()
                .putInt("s", lastPos.section).putInt("r", lastPos.row).putInt("c", lastPos.col)
                .putInt("dock", lastDockCol).apply();
    }

    private void restoreFocusMemory() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        lastPos = new TileView.Pos(sp.getInt("s", TileView.Pos.DOCK), sp.getInt("r", 0), sp.getInt("c", 0));
        lastDockCol = sp.getInt("dock", 0);
    }

    // ------------------------------------------------------------------ inputs menu

    private void toggleDropdown() {
        if (dropdown.getVisibility() == View.VISIBLE) closeDropdown();
        else openDropdown();
    }

    private void openDropdown() {
        dropdown.removeAllViews();
        dropdownItems.clear();
        String tuner = null;
        List<TvInputInfo> inputs = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info.getType() == TvInputInfo.TYPE_TUNER) {
                        if (tuner == null) tuner = info.getId();
                    } else if (info.isPassthroughInput()) {
                        inputs.add(info);
                    }
                }
            }
        } catch (Exception ignored) { }

        List<String[]> rows = new ArrayList<>(); // {label, inputId or "" for Live TV}
        for (TvInputInfo info : inputs) {
            if (info.isHidden(this)) continue;
            String label = cfg.inputLabels.get(info.getId());
            if (label == null) {
                CharSequence custom = info.loadCustomLabel(this);
                CharSequence base = info.loadLabel(this);
                label = custom != null && custom.length() > 0 ? custom.toString()
                        : base != null ? base.toString() : info.getId();
            }
            rows.add(new String[]{label, info.getId()});
        }
        Collections.sort(rows, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        rows.add(new String[]{"Live TV", ""});
        final String tunerId = tuner;

        for (String[] row : rows) {
            TextView item = text(18, Ui.regular, Color.WHITE);
            item.setText(row[0]);
            item.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12));
            item.setFocusable(true);
            item.setClickable(true);
            try { item.setDefaultFocusHighlightEnabled(false); } catch (Throwable ignored) { }
            item.setOnFocusChangeListener((v, has) ->
                    v.setBackground(has ? Ui.round(0x38FFFFFF, 12) : null));
            final String id = row[1];
            item.setOnClickListener(v -> {
                closeDropdown();
                if (id.isEmpty()) openLiveTv(tunerId);
                else switchInput(id);
            });
            dropdown.addView(item, new LinearLayout.LayoutParams(-1, -2));
            dropdownItems.add(item);
        }

        // Place it under the Inputs pill.
        View anchor = pills.get(0);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dropdown.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = Math.min(at[0], screenW - Ui.dp(300) - Ui.dp(SIDE));
        lp.topMargin = at[1] + anchor.getHeight() + Ui.dp(12);
        dropdown.setLayoutParams(lp);
        dropdown.setAlpha(0f);
        dropdown.setTranslationY(-Ui.dp(8));
        dropdown.setVisibility(View.VISIBLE);
        dropdown.animate().alpha(1f).translationY(0).setDuration(160).start();
        dropdownItems.get(0).requestFocus();
    }

    private void closeDropdown() {
        if (dropdown.getVisibility() != View.VISIBLE) return;
        dropdown.setVisibility(View.GONE);
        focusPill(0);
    }

    private void moveInDropdown(int k) {
        int i = dropdownItems.indexOf(getCurrentFocus());
        if (i < 0) i = 0;
        if (k == KeyEvent.KEYCODE_DPAD_UP) i--;
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) i++;
        dropdownItems.get(clamp(i, dropdownItems.size())).requestFocus();
    }

    private void switchInput(String inputId) {
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(i)) toast("This TV did not accept the input switch");
    }

    private void openLiveTv(String tunerId) {
        if (!cfg.liveTvPackage.isEmpty()) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(cfg.liveTvPackage);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(cfg.liveTvPackage);
            if (i != null && tryStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        }
        Uri uri = tunerId != null ? TvContract.buildChannelsUriForInput(tunerId) : TvContract.Channels.CONTENT_URI;
        if (!tryStart(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            toast("Live TV app not found. Set live_tv_package in config.json");
        }
    }

    // ------------------------------------------------------------------ actions

    private void launch(Apps.Entry app) {
        if (!tryStart(app.launchIntent())) toast(app.label + " could not be opened");
    }

    private void openSettings(String action) {
        if (!tryStart(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            tryStart(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void openSettingsIntent(Intent i) {
        if (!tryStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) toast("App info is not available on this TV");
    }

    private boolean tryStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ clock & network

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        String g = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
        greeting.setText(cfg.greetingName.isEmpty() ? g : g + ", " + cfg.greetingName);
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        Date d = now.getTime();
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(d));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(d));
    }

    private void updateNetwork() {
        String label = "Offline";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    label = "Ethernet";
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    label = "Wi‑Fi";
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
                    String ssid = wi != null ? wi.getSSID() : null;
                    if (ssid != null && !ssid.contains("unknown ssid")) {
                        ssid = ssid.replaceAll("^\"|\"$", "");
                        if (!ssid.isEmpty()) label = ssid;
                    }
                } else {
                    label = "Online";
                }
            }
        } catch (Exception ignored) { }
        wifiPill.setText(label);
    }

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // Install, uninstall, enable or disable: rebuild once things settle.
            main.removeCallbacks(reloadApps);
            main.postDelayed(reloadApps, 800);
        }
    };

    private void registerReceivers() {
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        // Event-driven, no polling: the pill only updates when the network changes.
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { main.post(MainActivity.this::updateNetwork); }
                @Override public void onLost(Network n) { main.post(MainActivity.this::updateNetwork); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                    main.post(MainActivity.this::updateNetwork);
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
        receiversOn = true;
    }

    private void unregisterReceivers() {
        if (!receiversOn) return;
        receiversOn = false;
        try { unregisterReceiver(timeReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        if (netCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) { }
        }
    }
}
