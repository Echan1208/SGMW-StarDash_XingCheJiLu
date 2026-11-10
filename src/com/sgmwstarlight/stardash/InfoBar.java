package com.sgmwstarlight.stardash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The status strip burned under the camera mosaic: date and time, speed, gear, then icons.
 * Redrawn a few times a second on its own thread; the capture thread only copies the NV12 bytes.
 */
final class InfoBar {
    static final int W = 1280, H = 48;
    private static final float CY = 24;
    private static final int BG = Color.BLACK, DIM = Color.rgb(78, 84, 94), TEXT = Color.rgb(235, 238, 242);
    private static final int GREEN = Color.rgb(61, 220, 151), AMBER = Color.rgb(255, 184, 0), RED = Color.rgb(255, 69, 72),
            BLUE = Color.rgb(77, 166, 255), WHITE = Color.WHITE;

    private final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final ByteBuffer rgba = ByteBuffer.allocateDirect(W * H * 4);
    private final ByteBuffer[] nv12 = {ByteBuffer.allocateDirect(W * H * 3 / 2), ByteBuffer.allocateDirect(W * H * 3 / 2)};
    private volatile ByteBuffer ready;
    private int next;
    private Bitmap snapshot;
    private final SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    InfoBar() {
        mono.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        mono.setTextSize(24);
        mono.setColor(TEXT);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    /** The bar as last drawn, for the app's home page. */
    synchronized Bitmap snapshot() {
        return snapshot;
    }

    /** Latest bar for the encoder, or null before the first draw. */
    ByteBuffer ready() {
        return ready;
    }

    void draw(VehicleState v, String mode) {
        canvas.drawColor(BG);
        float x = 14;
        canvas.drawText(clock.format(new Date()), x, 33, mono);
        x += mono.measureText("0000-00-00 00:00:00") + 26;

        // Speed stays as text.
        boolean speedKnown = v.known(VehicleState.SPEED);
        String speed = speedKnown ? String.valueOf(Math.round(v.speed())) : "--";
        x = drawText(x, speed, 28, speedKnown ? WHITE : DIM) + 5;
        x = drawText(x, "km/h", 16, DIM) + 20;

        // Gear letter in a rounded box.
        int gear = v.gear();
        int gearColor = gear == 12 ? GREEN : gear == 14 ? RED : gear == 10 ? BLUE : DIM;
        x = gearChip(x, VehicleState.gearName(gear), gearColor) + 22;

        // Steering wheel + angle
        steering(x + 15, TEXT);
        x += 36;
        String angle = v.known(VehicleState.STEER) ? String.format(Locale.US, "%+.0f°", v.steer()) : "--";
        x = drawText(x, angle, 21, TEXT) + 22;

        // Brake pedal: red while pressed.
        pedal(x, 26, 22, v.brake() ? RED : DIM);
        x += 26 + 18;

        // Accelerator pedal: green while pressed, plus 0-100 %.
        boolean accelKnown = v.known(VehicleState.ACCEL);
        int accel = accelKnown ? Math.max(0, Math.min(100, Math.round(v.accel()))) : 0;
        pedal(x, 15, 28, accel > 0 ? GREEN : DIM);
        x += 15 + 8;
        x = drawText(x, accelKnown ? accel + "%" : "--", 21, TEXT) + 22;

        // Electric parking brake (P) red when applied, AutoHold (A) green while holding.
        bracketBadge(x + 17, "P", v.epbApplied() ? RED : DIM);
        x += 34 + 16;
        bracketBadge(x + 17, "A", v.autoHold() ? GREEN : DIM);
        x += 34 + 22;

        // Turn signals
        arrow(x, true, v.leftTurn() ? GREEN : DIM);
        x += 22 + 8;
        arrow(x, false, v.rightTurn() ? GREEN : DIM);
        x += 22 + 22;

        // Lights: position (side) lights, low beam, high beam
        positionLights(x, v.positionLight() ? GREEN : DIM);
        x += 38 + 18;
        headlamp(x, false, v.lowBeam() ? GREEN : DIM);
        x += 34 + 16;
        headlamp(x, true, v.highBeam() ? BLUE : DIM);

        if (mode != null) {
            text.setTextSize(17);
            text.setColor(AMBER);
            canvas.drawText(mode, W - 14 - text.measureText(mode), 31, text);
        }

        rgba.rewind();
        bitmap.copyPixelsToBuffer(rgba);
        synchronized (this) {
            snapshot = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        ByteBuffer out = nv12[next];
        Native.rgbaToNv12(rgba, out, W, H);
        ready = out;
        next ^= 1;
    }

    // ------------------------------------------------------------------ drawing helpers

    private float drawText(float x, String s, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        canvas.drawText(s, x, CY + size * 0.36f, text);
        return x + text.measureText(s);
    }

    private float gearChip(float x, String g, int color) {
        text.setTextSize(22);
        float w = Math.max(30, text.measureText(g) + 16);
        rect.set(x, 10, x + w, 38);
        fill.setColor(color);
        canvas.drawRoundRect(rect, 7, 7, fill);
        text.setColor(BG);
        canvas.drawText(g, x + (w - text.measureText(g)) / 2, CY + 8, text);
        return x + w;
    }

    private void steering(float cx, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(3);
        canvas.drawCircle(cx, CY, 13, stroke);
        fill.setColor(color);
        canvas.drawCircle(cx, CY + 1, 4, fill);
        canvas.drawLine(cx - 12, CY - 1, cx - 4, CY + 1, stroke);
        canvas.drawLine(cx + 12, CY - 1, cx + 4, CY + 1, stroke);
        canvas.drawLine(cx, CY + 5, cx, CY + 12, stroke);
    }

    /** A pedal: rounded plate with grooves. */
    private void pedal(float x, float w, float h, int color) {
        float top = CY - h / 2;
        rect.set(x, top, x + w, top + h);
        fill.setColor(color);
        canvas.drawRoundRect(rect, 4, 4, fill);
        stroke.setColor(BG);
        stroke.setStrokeWidth(2);
        for (int i = 1; i <= 3; i++) {
            float y = top + h * i / 4f;
            canvas.drawLine(x + 3, y, x + w - 3, y, stroke);
        }
    }

    /** Dashboard-style (X) badge: a letter in a circle with arcs either side. */
    private void bracketBadge(float cx, String letter, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(2.5f);
        canvas.drawCircle(cx, CY, 10, stroke);
        rect.set(cx - 16, CY - 16, cx + 16, CY + 16);
        canvas.drawArc(rect, 135, 90, false, stroke);
        canvas.drawArc(rect, -45, 90, false, stroke);
        text.setTextSize(15);
        text.setColor(color);
        canvas.drawText(letter, cx - text.measureText(letter) / 2, CY + 5.5f, text);
    }

    private void arrow(float x, boolean left, int color) {
        fill.setColor(color);
        path.reset();
        if (left) {
            path.moveTo(x, CY);
            path.lineTo(x + 22, CY - 13);
            path.lineTo(x + 22, CY + 13);
        } else {
            path.moveTo(x + 22, CY);
            path.lineTo(x, CY - 13);
            path.lineTo(x, CY + 13);
        }
        path.close();
        canvas.drawPath(path, fill);
    }

    /** Side/position lights: two small lamps facing outward with short rays. */
    private void positionLights(float x, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(2.5f);
        float l = x + 12, r = x + 26;
        rect.set(l - 7, CY - 7, l + 7, CY + 7);
        canvas.drawArc(rect, 90, 180, false, stroke);
        rect.set(r - 7, CY - 7, r + 7, CY + 7);
        canvas.drawArc(rect, -90, 180, false, stroke);
        for (int i = -1; i <= 1; i++) {
            canvas.drawLine(x, CY + i * 7, x + 4, CY + i * 6, stroke);
            canvas.drawLine(x + 34, CY + i * 6, x + 38, CY + i * 7, stroke);
        }
    }

    /** Headlamp: a lamp body on the right, rays to the left; dipped rays slant down. */
    private void headlamp(float x, boolean high, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(2.5f);
        float body = x + 16;
        path.reset();
        path.moveTo(body, CY - 10);
        path.cubicTo(body + 22, CY - 10, body + 22, CY + 10, body, CY + 10);
        path.close();
        if (high) {
            fill.setColor(color);
            canvas.drawPath(path, fill);
        } else {
            canvas.drawPath(path, stroke);
        }
        for (int i = -1; i <= 1; i++) {
            float y = CY + i * 7;
            canvas.drawLine(x, high ? y : y + 4, x + 11, y, stroke);
        }
    }
}
