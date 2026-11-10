package com.sgmwstarlight.stardash;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared look: dark surfaces, one accent colour, rounded cards. */
final class Ui {
    static final int BG = Color.rgb(11, 14, 19), CARD = Color.rgb(21, 26, 33), CARD2 = Color.rgb(30, 36, 45),
            TEXT = Color.rgb(233, 237, 242), SUB = Color.rgb(138, 148, 163), LINE = Color.rgb(40, 47, 58),
            GREEN = Color.rgb(61, 220, 151), RED = Color.rgb(255, 77, 79), AMBER = Color.rgb(255, 184, 0),
            BLUE = Color.rgb(77, 166, 255);

    private Ui() {
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    static GradientDrawable outline(int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(2, stroke);
        d.setCornerRadius(radius);
        return d;
    }

    static TextView text(Context c, String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout card(Context c, int orientation) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        l.setBackground(round(CARD, 18));
        l.setPadding(28, 24, 28, 24);
        return l;
    }

    static TextView button(Context c, String s, int bg, int fg) {
        TextView b = text(c, s, 20, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(bg, 14));
        b.setPadding(32, 18, 32, 18);
        b.setClickable(true);
        return b;
    }

    static TextView chip(Context c, String s, boolean on, int color) {
        TextView t = text(c, s, 16, on ? BG : SUB, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(20, 8, 20, 8);
        t.setBackground(on ? round(color, 12) : outline(LINE, 12));
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }

    static View space(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, h));
        return v;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    static String size(long bytes) {
        if (bytes >= (1L << 30)) return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        return String.format(java.util.Locale.US, "%.0f MB", bytes / (double) (1L << 20));
    }
}
