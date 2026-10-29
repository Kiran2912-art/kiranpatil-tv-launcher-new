package com.kiranpatil.tvlauncher;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Full-screen frame whose child may be taller than the screen. The child is scrolled by
 * animating its translationY, so focus never triggers the system's own auto-scroll and
 * Up/Down always move exactly one row.
 */
final class Stage extends FrameLayout {
    Stage(Context c) {
        super(c);
        setClipChildren(true);
    }

    @Override
    protected void measureChildWithMargins(View child, int widthSpec, int widthUsed,
                                           int heightSpec, int heightUsed) {
        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        int w = getChildMeasureSpec(widthSpec,
                getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin + widthUsed, lp.width);
        int h = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        child.measure(w, h);
    }
}
