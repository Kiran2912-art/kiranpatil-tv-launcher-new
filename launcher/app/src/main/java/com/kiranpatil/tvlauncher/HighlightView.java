package com.kiranpatil.tvlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * The single translucent "glass" highlight. It springs from tile to tile instead of
 * each tile drawing its own border. Drawn as one rounded rect, no blur, cheap on a 1 GB TV.
 */
final class HighlightView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cur = new RectF();
    private final RectF from = new RectF();
    private final RectF to = new RectF();
    private final RectF tmp = new RectF();
    private ValueAnimator anim;
    private boolean placed;
    private final float radius;

    HighlightView(Context c) {
        super(c);
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
        radius = Ui.dp(TileView.RADIUS_DP * TileView.FOCUS_SCALE + 2);
        fill.setColor(0x24FFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dp(1.5f));
        edge.setColor(0x73FFFFFF);
        sheen.setColor(0x14FFFFFF);
        setAlpha(0f);
    }

    void moveTo(RectF target, boolean animate) {
        to.set(target);
        if (anim != null) anim.cancel();
        if (!placed || !animate) {
            cur.set(to);
            placed = true;
            invalidate();
            return;
        }
        from.set(cur);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(320);
        anim.setInterpolator(new OvershootInterpolator(1.1f));
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            cur.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
            invalidate();
        });
        anim.start();
    }

    void show(boolean visible) {
        animate().alpha(visible ? 1f : 0f).setDuration(visible ? 160 : 120).start();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!placed) return;
        c.drawRoundRect(cur, radius, radius, fill);
        // A soft lighter band across the top half reads as glass without any blur.
        tmp.set(cur.left, cur.top, cur.right, cur.top + cur.height() * 0.45f);
        c.save();
        c.clipRect(tmp);
        c.drawRoundRect(cur, radius, radius, sheen);
        c.restore();
        c.drawRoundRect(cur, radius, radius, edge);
    }
}
