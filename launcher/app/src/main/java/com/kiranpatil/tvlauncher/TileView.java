package com.kiranpatil.tvlauncher;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One app tile: a rounded 16:9 card with the app's banner, and its name shown on focus. */
final class TileView extends LinearLayout {
    static final float FOCUS_SCALE = 1.08f;
    static final float RADIUS_DP = 14f;

    final Apps.Entry app;
    final FrameLayout card;
    private final TextView name;
    final int cardW, cardH;

    TileView(Context c, Apps.Entry app, int w, int h) {
        super(c);
        this.app = app;
        this.cardW = w;
        this.cardH = h;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setDefaultFocusHighlightEnabledCompat();

        card = new FrameLayout(c);
        card.setBackground(Ui.round(0xFF1C1C1E, RADIUS_DP));
        Ui.clipRound(card, RADIUS_DP);

        if (app.banner != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(app.banner);
            card.addView(iv, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        } else {
            // No TV banner: icon over a dark card, with the label, like a plain banner.
            LinearLayout inner = new LinearLayout(c);
            inner.setOrientation(VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(app.icon);
            int s = Math.round(h * 0.42f);
            inner.addView(iv, new LinearLayout.LayoutParams(s, s));
            TextView t = new TextView(c);
            t.setText(app.label);
            t.setTextColor(0xCCFFFFFF);
            t.setTypeface(Ui.regular);
            t.setTextSize(13);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            tl.topMargin = Ui.dp(6);
            inner.addView(t, tl);
            card.addView(inner, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        addView(card, new LinearLayout.LayoutParams(w, h));

        name = new TextView(c);
        name.setText(app.label);
        name.setTextColor(Color.WHITE);
        name.setTypeface(Ui.medium);
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setAlpha(0f);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(w, LayoutParams.WRAP_CONTENT);
        nl.topMargin = Ui.dp(14);
        addView(name, nl);

        setPivotX(w / 2f);
        setPivotY(h / 2f);
    }

    void showFocus(boolean focused) {
        animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f)
                .setDuration(180).start();
        name.animate().alpha(focused ? 0.95f : 0f).setDuration(focused ? 200 : 120).start();
    }

    /** Hides the system's own focus overlay (Android 8+), the glass highlight replaces it. */
    private void setDefaultFocusHighlightEnabledCompat() {
        try {
            setDefaultFocusHighlightEnabled(false);
        } catch (Throwable ignored) { }
    }

    /** Tag for deterministic D-pad navigation. */
    static final class Pos {
        static final int TOP = 0, DOCK = 1, GRID = 2;
        final int section, row, col;

        Pos(int section, int row, int col) {
            this.section = section;
            this.row = row;
            this.col = col;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    static View spacer(Context c, int w) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }
}
