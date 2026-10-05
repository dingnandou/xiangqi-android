package com.yijin.xiangqi.light;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Wood sprite and centered lettering sampled from the approved design artwork. */
final class WoodPiecePainter {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Glyph> glyphs = new HashMap<>();
    private final Bitmap wood;
    // The face, rather than the lower edge/shadow, is anchored to the board intersection.
    private static final float FACE_X = .492f, FACE_Y = .452f, BODY_RADIUS = .439f;
    private final RectF woodBounds = new RectF(-FACE_X / BODY_RADIUS, -FACE_Y / BODY_RADIUS,
            (1 - FACE_X) / BODY_RADIUS, (1 - FACE_Y) / BODY_RADIUS);

    WoodPiecePainter(Context context) {
        try (InputStream source = context.getAssets().open("wood-piece.png")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            options.inScaled = false;
            wood = BitmapFactory.decodeStream(source, null, options);
            if (wood == null) throw new IOException("Invalid wood piece sprite");
        } catch (IOException error) {
            throw new IllegalStateException("Missing wood piece asset", error);
        }
        try (InputStream source = context.getAssets().open("piece-lettering-reference.png")) {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false;
            Bitmap reference = BitmapFactory.decodeStream(source, null, options);
            if (reference == null || reference.getWidth() != 853 || reference.getHeight() != 1843)
                throw new IOException("Invalid lettering reference");
            try {
                addGlyph(reference, "车", false, 73, 461);
                addGlyph(reference, "马", false, 692, 461);
                addGlyph(reference, "象", false, 245, 461);
                addGlyph(reference, "士", false, 335, 461);
                addGlyph(reference, "将", false, 425, 461);
                addGlyph(reference, "炮", false, 155, 639);
                addGlyph(reference, "卒", false, 425, 732);
                addGlyph(reference, "车", true, 73, 1262);
                addGlyph(reference, "马", true, 155, 1262);
                addGlyph(reference, "相", true, 245, 1262);
                addGlyph(reference, "仕", true, 335, 1262);
                addGlyph(reference, "帅", true, 425, 1262);
                addGlyph(reference, "炮", true, 155, 1091);
                addGlyph(reference, "兵", true, 245, 1002);
            } finally { reference.recycle(); }
        } catch (IOException error) {
            throw new IllegalStateException("Missing chess lettering artwork", error);
        }
    }

    private static final class Glyph {
        final Bitmap bitmap;
        final SparseArray<RasterGlyph> sizes = new SparseArray<>();
        Glyph(Bitmap bitmap) {
            this.bitmap = bitmap;
        }
        RasterGlyph atSize(float radius) {
            int size = Math.max(1, Math.round(radius * 1.28f));
            RasterGlyph cached = sizes.get(size);
            if (cached != null) return cached;
            float scale = size / (float)Math.max(bitmap.getWidth(), bitmap.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            cached = new RasterGlyph(scaled); sizes.put(size, cached); return cached;
        }
    }

    private static final class RasterGlyph {
        final Bitmap bitmap;
        final float centerX, centerY;
        RasterGlyph(Bitmap bitmap) {
            this.bitmap = bitmap;
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height]; bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            int minX = width, minY = height, maxX = -1, maxY = -1;
            for (int i = 0; i < pixels.length; i++) {
                if ((pixels[i] >>> 24) < 32) continue;
                int x = i % width, y = i / width;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            // Center the visible ink after downsampling; faint tips change at small sizes.
            centerX = maxX >= minX ? (minX + maxX + 1) / 2f : width / 2f;
            centerY = maxY >= minY ? (minY + maxY + 1) / 2f : height / 2f;
        }
    }

    private void addGlyph(Bitmap reference, String name, boolean red, int cx, int cy) {
        int size = 70, half = size / 2, minX = size, minY = size, maxX = -1, maxY = -1;
        int[] pixels = new int[size * size];
        reference.getPixels(pixels, 0, size, cx - half, cy - half, size, size);
        for (int i = 0; i < pixels.length; i++) {
            int r = (pixels[i] >> 16) & 255, g = (pixels[i] >> 8) & 255, b = pixels[i] & 255;
            float dx = i % size - half + .5f, dy = i / size - half + 5.5f;
            boolean onFace = dx * dx + dy * dy <= 37 * 37;
            boolean ink = red ? r < 240 && r > 2.35f * g && r - g > 65 && g - b < 48
                    : r < 185 && r - g < 45 && g - b < 45;
            ink &= onFace;
            int alpha = ink ? red ? Math.min(255, (r - g - 45) * 255 / 55)
                    : Math.min(255, (225 - r) * 255 / 193) : 0;
            pixels[i] = alpha > 0 ? (alpha << 24) | (red ? 0x00a02618 : 0x00201a16) : 0;
            if (alpha > 0) {
                int x = i % size, y = i / size;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX || maxY < minY) throw new IllegalStateException("Missing chess artwork: " + name);
        Bitmap mask = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        Bitmap cropped = Bitmap.createBitmap(mask, minX, minY, maxX - minX + 1, maxY - minY + 1);
        if (cropped != mask) mask.recycle();
        glyphs.put((red ? "R" : "B") + name, new Glyph(cropped));
    }

    void draw(Canvas canvas, float x, float y, float radius, String name, boolean red) {
        if (radius <= 0) return;
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(radius, radius);
        canvas.drawBitmap(wood, null, woodBounds, bitmapPaint);
        canvas.restore();
        Glyph glyph = glyphs.get((red ? "R" : "B") + name);
        if (glyph != null) {
            RasterGlyph raster = glyph.atSize(radius);
            canvas.drawBitmap(raster.bitmap, x - raster.centerX, y - raster.centerY, bitmapPaint);
        }
    }
}
