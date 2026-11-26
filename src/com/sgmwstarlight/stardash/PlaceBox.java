package com.sgmwstarlight.stardash;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "调整画面位置": a box with the exact size of a side floating view, shown as the same kind of overlay
 * window so its screen position is the floating view's. Drag it anywhere; pick 小/中/大; 保存 stores
 * both for that side, 恢复默认 puts the box back to the default size and spot (saved only on 保存).
 */
final class PlaceBox extends FrameLayout {
    interface Listener {
        void onSaved();

        void onClosed();
    }

    private final Config config;
    private final MirrorController.Side side;
    private final WindowManager wm;
    private final Listener listener;
    private final WindowManager.LayoutParams lp;
    private final TextView[] chips = new TextView[3];
    private int size;
    private float downX, downY;
    private int startX, startY;
    private boolean attached;

    PlaceBox(Context c, Config config, MirrorController.Side side, Listener listener) {
        super(c);
        this.config = config;
        this.side = side;
        this.listener = listener;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);

        GradientDrawable bg = Ui.round(Color.argb(170, 0, 0, 0), 16);
        bg.setStroke(4, Ui.BLUE);
        setBackground(bg);

        String name = side == MirrorController.Side.LEFT ? "左后方" : "右后方";
        TextView tip = Ui.text(c, name + " · 拖动方框调整位置", 15, Ui.TEXT, false);
        LayoutParams tlp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        tlp.setMargins(16, 12, 0, 0);
        addView(tip, tlp);

        TextView close = Ui.text(c, "✕", 22, Ui.SUB, true);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> close());
        addView(close, new LayoutParams(56, 56, Gravity.TOP | Gravity.RIGHT));

        LinearLayout centre = new LinearLayout(c);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout sizes = new LinearLayout(c);
        String[] names = {"小", "中", "大"};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            TextView chip = Ui.text(c, names[i], 20, Ui.SUB, true);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> setSize(index));
            chips[i] = chip;
            sizes.addView(chip, Ui.margins(Ui.lp(76, 52), i == 0 ? 0 : 12, 0, 0, 0));
        }
        centre.addView(sizes);
        LinearLayout buttons = new LinearLayout(c);
        TextView save = Ui.button(c, "保存", Ui.GREEN, Ui.BG);
        save.setTextSize(18);
        save.setPadding(0, 0, 0, 0);
        save.setOnClickListener(v -> save());
        TextView reset = Ui.button(c, "恢复默认", Ui.CARD2, Ui.AMBER);
        reset.setTextSize(18);
        reset.setPadding(0, 0, 0, 0);
        reset.setOnClickListener(v -> resetDefault());
        buttons.addView(save, Ui.lp(128, 52));
        buttons.addView(reset, Ui.margins(Ui.lp(128, 52), 14, 0, 0, 0));
        centre.addView(buttons, Ui.margins(Ui.lp(-2, -2), 0, 16, 0, 0));
        addView(centre, new LayoutParams(-2, -2, Gravity.CENTER));

        size = MirrorController.sizeOf(config, side);
        int w = width(size), h = height(size);
        int[] p = MirrorController.sidePos(config, side, w, h);
        lp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = p[0];
        lp.y = p[1];
        lp.setTitle("StarDashPlaceBox");
        markChips();
    }

    /** Adds the overlay; false when the overlay permission is missing. */
    boolean open() {
        try {
            wm.addView(this, lp);
            attached = true;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    void close() {
        if (!attached) return;
        attached = false;
        try {
            wm.removeView(this);
        } catch (RuntimeException ignored) {
        }
        listener.onClosed();
    }

    private int width(int s) {
        return MirrorController.windowWidth(side, MirrorController.SIZE_SCALES[s]);
    }

    private int height(int s) {
        return MirrorController.windowHeight(side, MirrorController.SIZE_SCALES[s]);
    }

    private void markChips() {
        for (int i = 0; i < chips.length; i++) {
            chips[i].setTextColor(i == size ? Ui.BG : Ui.TEXT);
            chips[i].setBackground(i == size ? Ui.round(Ui.BLUE, 12) : Ui.round(Color.argb(150, 60, 68, 80), 12));
        }
    }

    /** Resizes about the top-left corner, kept on screen. */
    private void setSize(int s) {
        size = s;
        lp.width = width(s);
        lp.height = height(s);
        moveTo(lp.x, lp.y);
        markChips();
    }

    private void resetDefault() {
        size = 1;
        lp.width = width(size);
        lp.height = height(size);
        int[] p = MirrorController.defaultPos(side, lp.width);
        moveTo(p[0], p[1]);
        markChips();
    }

    private void save() {
        int[] def = MirrorController.defaultPos(side, lp.width);
        boolean atDefault = lp.x == def[0] && lp.y == def[1];
        config.putSideWindow(side.name(), size, atDefault ? -1 : lp.x, lp.y);
        listener.onSaved();
        close();
    }

    private void moveTo(int x, int y) {
        lp.x = Math.max(0, Math.min(MirrorController.SCREEN_W - lp.width, x));
        lp.y = Math.max(0, Math.min(MirrorController.SCREEN_H - lp.height, y));
        if (!attached) return;
        try {
            wm.updateViewLayout(this, lp);
        } catch (RuntimeException ignored) {
        }
    }

    /** Touches that miss the buttons drag the box. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                moveTo(startX + Math.round(e.getRawX() - downX), startY + Math.round(e.getRawY() - downY));
                return true;
            default:
                return true;
        }
    }
}
