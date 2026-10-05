package com.yijin.xiangqi.light;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Rounded wooden pieces. Unit-space shaders are reused at every screen size. */
final class WoodPiecePainter {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface typeface = Typeface.create("serif", Typeface.BOLD);
    private final Shader shadow = new RadialGradient(.04f, .13f, 1.14f,
            new int[]{0x60452a15, 0x45452a15, Color.TRANSPARENT},
            new float[]{0f, .77f, 1f}, Shader.TileMode.CLAMP);
    private final Shader edge = new LinearGradient(0, -1, 0, 1.04f,
            new int[]{0xffedc18a, 0xffc98a4e, 0xff995a2c},
            new float[]{0f, .7f, 1f}, Shader.TileMode.CLAMP);
    private final Shader bevel = new LinearGradient(-.6f, -.9f, .55f, .95f,
            new int[]{0xffffeac0, 0xfff6cf92, 0xffd89d5c, 0xffb8783f},
            new float[]{0f, .42f, .8f, 1f}, Shader.TileMode.CLAMP);
    private final Shader face = new RadialGradient(-.36f, -.48f, 1.65f,
            new int[]{0xffffe6b5, 0xfff3ce95, 0xffe8b77c},
            new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP);
    private final Path faceClip = new Path();
    private final Path grain = new Path();

    WoodPiecePainter() {
        paint.setStrokeCap(Paint.Cap.ROUND);
        faceClip.addCircle(0, -.055f, .855f, Path.Direction.CW);
        // Faint, curved grain stays below the lettering and is clipped to the face.
        for (int i = 0; i < 7; i++) {
            float x = -.72f + i * .23f;
            grain.moveTo(x, -1);
            grain.cubicTo(x + .1f, -.5f, x - .09f, .23f, x + .035f, 1);
        }
    }

    private void fill(Shader shader) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShader(shader);
    }

    private void stroke(int color, float width) {
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
    }

    void draw(Canvas canvas, float x, float y, float radius, String name, boolean red) {
        if (radius <= 0) return;
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(radius, radius);

        fill(shadow);
        canvas.drawCircle(.04f, .13f, 1.14f, paint);
        // The lower edge gives depth without changing the round touch target.
        fill(edge);
        canvas.drawCircle(0, .04f, 1f, paint);
        stroke(0x886e3e20, .018f);
        canvas.drawCircle(0, .04f, .991f, paint);
        fill(bevel);
        canvas.drawCircle(0, -.045f, .975f, paint);
        fill(face);
        canvas.drawCircle(0, -.055f, .855f, paint);

        canvas.save();
        canvas.clipPath(faceClip);
        stroke(0x12a5723b, .012f);
        canvas.drawPath(grain, paint);
        canvas.restore();

        // Thin wood engraving and soft curved highlights replace colored double rings.
        stroke(0x447f4e29, .012f);
        canvas.drawCircle(0, -.055f, .842f, paint);
        stroke(0xb3fff3d8, .028f);
        canvas.drawArc(-.932f, -.977f, .932f, .887f, 202, 137, false, paint);
        stroke(0x657c4828, .022f);
        canvas.drawArc(-.941f, -.986f, .941f, .896f, 20, 125, false, paint);

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(typeface);
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(1.34f);
        float baseline = -.055f - (paint.ascent() + paint.descent()) / 2;
        // A restrained engraving highlight keeps the characters crisp on small screens.
        paint.setColor(0x8cfff0d2);
        canvas.drawText(name, .01f, baseline + .018f, paint);
        paint.setColor(red ? 0xffa32e22 : 0xff29251f);
        canvas.drawText(name, 0, baseline, paint);
        canvas.restore();
    }
}
