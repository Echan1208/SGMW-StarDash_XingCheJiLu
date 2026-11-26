package com.sgmwstarlight.stardash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.nio.ByteBuffer;

/**
 * "前 后 左 右" tags in the top-left corner of each camera in the 2x2 mosaic
 * (quadrant 0 front, 1 rear, 2 left, 3 right), drawn into the encoder's NV12 frame and onto the preview.
 */
final class CamLabels {
    static final String[] NAMES = {"前", "后", "左", "右"};
    /** Size of one camera in the recorded 1280x960 frame. */
    private static final int CAM_W = 640, CAM_H = 480;
    private static final int MARGIN = 14, TEXT_PX = 34, PAD_X = 12, PAD_Y = 6;
    private static final int BOX_ALPHA = 140;

    /** One tag pre-rendered as luma, per-pixel alpha, and per-2x2 alpha for the chroma plane. */
    private static final class Tag {
        int w, h;
        byte[] luma;
        short[] alpha, uvAlpha;
    }

    private static Tag[] tags;

    private CamLabels() {
    }

    private static synchronized Tag[] tags() {
        if (tags != null) return tags;
        Paint text = textPaint(TEXT_PX);
        Paint.FontMetrics fm = text.getFontMetrics();
        Tag[] out = new Tag[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            int w = even((int) Math.ceil(text.measureText(NAMES[i])) + PAD_X * 2);
            int h = even((int) Math.ceil(fm.descent - fm.ascent) + PAD_Y * 2);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            drawTag(c, NAMES[i], 0, 0, w, h, text);
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();
            Tag t = new Tag();
            t.w = w;
            t.h = h;
            t.luma = new byte[w * h];
            t.alpha = new short[w * h];
            t.uvAlpha = new short[(w / 2) * (h / 2)];
            for (int k = 0; k < px.length; k++) {
                int a = px[k] >>> 24;
                if (a == 0) continue;
                // Pixels are premultiplied-free ARGB from getPixels; convert the colour to BT.601 limited luma.
                int r = (px[k] >> 16) & 0xff, g = (px[k] >> 8) & 0xff, bl = px[k] & 0xff;
                t.luma[k] = (byte) Math.min(235, ((66 * r + 129 * g + 25 * bl + 128) >> 8) + 16);
                t.alpha[k] = (short) a;
            }
            for (int y = 0; y < h / 2; y++) {
                for (int x = 0; x < w / 2; x++) {
                    int k = y * 2 * w + x * 2;
                    t.uvAlpha[y * (w / 2) + x] = (short) ((t.alpha[k] + t.alpha[k + 1] + t.alpha[k + w] + t.alpha[k + w + 1]) / 4);
                }
            }
            out[i] = t;
        }
        tags = out;
        return out;
    }

    /** Blends the four tags into an NV12 frame whose cameras fill the top 960 rows. */
    static void drawNv12(ByteBuffer y, int yStride, ByteBuffer uv, int uvStride) {
        Tag[] all = tags();
        for (int q = 0; q < all.length; q++) {
            Tag t = all[q];
            int x0 = (q % 2) * CAM_W + MARGIN, y0 = (q / 2) * CAM_H + MARGIN;
            for (int row = 0; row < t.h; row++) {
                int base = (y0 + row) * yStride + x0;
                int k = row * t.w;
                for (int col = 0; col < t.w; col++, k++) {
                    int a = t.alpha[k];
                    if (a == 0) continue;
                    int at = base + col;
                    int old = y.get(at) & 0xff;
                    y.put(at, (byte) ((old * (255 - a) + (t.luma[k] & 0xff) * a + 127) / 255));
                }
            }
            // Tags are white on grey, so their chroma is neutral: pull U and V toward 128.
            for (int row = 0; row < t.h / 2; row++) {
                int base = (y0 / 2 + row) * uvStride + x0;
                int k = row * (t.w / 2);
                for (int col = 0; col < t.w / 2; col++, k++) {
                    int a = t.uvAlpha[k];
                    if (a == 0) continue;
                    int at = base + col * 2;
                    int u = uv.get(at) & 0xff, v = uv.get(at + 1) & 0xff;
                    uv.put(at, (byte) ((u * (255 - a) + 128 * a + 127) / 255));
                    uv.put(at + 1, (byte) ((v * (255 - a) + 128 * a + 127) / 255));
                }
            }
        }
    }

    /** Draws the tags on the in-app preview (the same 2x2 mosaic at any size). */
    static void drawPreview(Bitmap b) {
        Canvas c = new Canvas(b);
        float scale = b.getWidth() / 1280f;
        Paint text = textPaint(TEXT_PX * scale);
        Paint.FontMetrics fm = text.getFontMetrics();
        for (int q = 0; q < NAMES.length; q++) {
            float w = text.measureText(NAMES[q]) + PAD_X * 2 * scale;
            float h = fm.descent - fm.ascent + PAD_Y * 2 * scale;
            float x0 = (q % 2) * b.getWidth() / 2f + MARGIN * scale, y0 = (q / 2) * b.getHeight() / 2f + MARGIN * scale;
            drawTag(c, NAMES[q], x0, y0, w, h, text);
        }
    }

    private static void drawTag(Canvas c, String name, float x0, float y0, float w, float h, Paint text) {
        Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        box.setColor(Color.argb(BOX_ALPHA, 0, 0, 0));
        float r = h * 0.18f;
        c.drawRoundRect(new RectF(x0, y0, x0 + w, y0 + h), r, r, box);
        Paint.FontMetrics fm = text.getFontMetrics();
        float tx = x0 + (w - text.measureText(name)) / 2;
        float ty = y0 + (h - (fm.descent - fm.ascent)) / 2 - fm.ascent;
        c.drawText(name, tx, ty, text);
    }

    private static Paint textPaint(float px) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(px);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return p;
    }

    private static int even(int v) {
        return (v + 1) & ~1;
    }
}
