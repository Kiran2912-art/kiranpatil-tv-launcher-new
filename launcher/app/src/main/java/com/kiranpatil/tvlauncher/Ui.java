package com.kiranpatil.tvlauncher;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Sizes, fonts and shapes shared by the launcher's views. */
final class Ui {
    private Ui() {}

    static float density = 2f;
    static Typeface thin, light, regular, medium;

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        AssetManager am = c.getAssets();
        thin = font(am, 200);
        light = font(am, 300);
        regular = font(am, 400);
        medium = font(am, 500);
    }

    /** Outfit is a variable font; one file gives every weight. */
    private static Typeface font(AssetManager am, int weight) {
        try {
            Typeface t = new Typeface.Builder(am, "fonts/Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            return t != null ? t : Typeface.DEFAULT;
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static GradientDrawable round(int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = round(color, radiusDp);
        g.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return g;
    }

    static void clipRound(View v, final float radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    /**
     * Average colour of a banner, ignoring near-black and transparent pixels, then
     * lifted so it reads as a glow on black. Runs once per dock app.
     */
    static int sampleColor(Drawable d) {
        if (d == null) return 0xFF8E8E93;
        try {
            Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(0, 0, 32, 18);
            copy.draw(c);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 18; y++) {
                for (int x = 0; x < 32; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    int pr = Color.red(p), pg = Color.green(p), pb = Color.blue(p);
                    if (pr + pg + pb < 60) continue;          // skip near-black
                    if (pr > 235 && pg > 235 && pb > 235) continue; // skip pure white text
                    r += pr; g += pg; bl += pb; n++;
                }
            }
            b.recycle();
            if (n == 0) return 0xFF8E8E93;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.25f);
            hsv[2] = Math.max(0.75f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF8E8E93;
        }
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
