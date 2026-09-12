package lab.xfold.duo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

final class DuoOverlayView extends View {
    interface SizeModeListener { void onCoverModeChanged(boolean cover); }

    // Inspired by the fixed-projection + progressive-blur technique used by open-source
    // iPhone Duo studies, re-derived here for a real foldable display.
    private static final String AGSL =
            "uniform shader content;\n" +
            "uniform float2 destSize;\n" +
            "uniform float2 sourceSize;\n" +
            "uniform float foldProgress;\n" +
            "uniform float coverProgress;\n" +
            "uniform float mode;\n" +
            "uniform float movingSide;\n" +
            "\n" +
            "half4 sampleBlur(float2 suv, float radius) {\n" +
            "  float2 sp = clamp(suv, float2(0.0), float2(1.0)) * sourceSize;\n" +
            "  if (radius < 0.6) return content.eval(sp);\n" +
            "  float r = radius;\n" +
            "  half4 c = content.eval(sp) * 0.20;\n" +
            "  c += content.eval(sp + float2( r, 0.0)) * 0.10;\n" +
            "  c += content.eval(sp + float2(-r, 0.0)) * 0.10;\n" +
            "  c += content.eval(sp + float2(0.0,  r)) * 0.10;\n" +
            "  c += content.eval(sp + float2(0.0, -r)) * 0.10;\n" +
            "  float q = r * 0.72;\n" +
            "  c += content.eval(sp + float2( q,  q)) * 0.075;\n" +
            "  c += content.eval(sp + float2(-q,  q)) * 0.075;\n" +
            "  c += content.eval(sp + float2( q, -q)) * 0.075;\n" +
            "  c += content.eval(sp + float2(-q, -q)) * 0.075;\n" +
            "  float h = r * 0.42;\n" +
            "  c += content.eval(sp + float2( h, 0.0)) * 0.025;\n" +
            "  c += content.eval(sp + float2(-h, 0.0)) * 0.025;\n" +
            "  c += content.eval(sp + float2(0.0,  h)) * 0.025;\n" +
            "  c += content.eval(sp + float2(0.0, -h)) * 0.025;\n" +
            "  return c;\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 p) {\n" +
            "  float2 uv = p / max(destSize, float2(1.0));\n" +
            "  float2 suv = uv;\n" +
            "  float radius = 0.0;\n" +
            "  float dark = 0.0;\n" +
            "  float outAlpha = 1.0;\n" +
            "\n" +
            "  if (mode < 0.5) {\n" +
            "    float motion = smoothstep(0.0, 1.0, foldProgress);\n" +
            "    float edge;\n" +
            "    if (movingSide > 0.5) {\n" +
            "      edge = clamp((uv.x - 0.5) * 2.0, 0.0, 1.0);\n" +
            "      if (uv.x > 0.5) {\n" +
            "        float projection = mix(1.0, 0.075, pow(motion, 0.88));\n" +
            "        suv.x = 0.5 + (uv.x - 0.5) * projection;\n" +
            "      }\n" +
            "    } else {\n" +
            "      edge = clamp((0.5 - uv.x) * 2.0, 0.0, 1.0);\n" +
            "      if (uv.x < 0.5) {\n" +
            "        float projection = mix(1.0, 0.075, pow(motion, 0.88));\n" +
            "        suv.x = 0.5 - (0.5 - uv.x) * projection;\n" +
            "      }\n" +
            "    }\n" +
            "    float blurGradient = pow(edge, 1.32);\n" +
            "    float darkGradient = pow(clamp((edge - 0.16) / 0.84, 0.0, 1.0), 1.35);\n" +
            "    radius = 92.0 * motion * blurGradient * (sourceSize.x / 1440.0);\n" +
            "    dark = min(1.0, motion * darkGradient * 1.90);\n" +
            "  } else {\n" +
            "    // After OriginOS switches to the narrow cover display, project the matching\n" +
            "    // half of the frozen inner frame, then fade it away to the real cover UI.\n" +
            "    if (movingSide > 0.5) suv.x = 0.5 + uv.x * 0.5;\n" +
            "    else suv.x = uv.x * 0.5;\n" +
            "    float remain = 1.0 - smoothstep(0.0, 1.0, coverProgress);\n" +
            "    radius = 46.0 * remain * (sourceSize.x / 1440.0);\n" +
            "    dark = 0.22 * remain;\n" +
            "    outAlpha = remain;\n" +
            "  }\n" +
            "\n" +
            "  half4 c = sampleBlur(suv, radius);\n" +
            "  c.rgb *= half(1.0 - dark);\n" +
            "  c.a *= half(outAlpha);\n" +
            "  return c;\n" +
            "}\n";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RuntimeShader shader;
    private Bitmap snapshot;
    private BitmapShader bitmapShader;
    private float angle = 180f;
    private boolean coverMode = false;
    private long coverStartMs = 0L;
    private SizeModeListener sizeModeListener;

    DuoOverlayView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        shader = new RuntimeShader(AGSL);
        paint.setShader(shader);
    }

    void setSizeModeListener(SizeModeListener l) { sizeModeListener = l; }

    void setSnapshot(Bitmap bitmap) {
        snapshot = bitmap;
        bitmapShader = bitmap == null ? null : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (bitmapShader != null) shader.setInputShader("content", bitmapShader);
        invalidate();
    }

    void setAngle(float degrees) {
        angle = clamp(degrees, 0f, 180f);
        invalidate();
    }

    void beginCoverReveal() {
        coverMode = true;
        coverStartMs = SystemClock.uptimeMillis();
        invalidate();
    }

    void setInnerMode() {
        coverMode = false;
        coverStartMs = 0L;
        invalidate();
    }

    boolean isCoverMode() { return coverMode; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        boolean cover = isCoverAspect(w, h);
        if (sizeModeListener != null && w > 0 && h > 0) {
            post(() -> sizeModeListener.onCoverModeChanged(cover));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (snapshot == null || bitmapShader == null || getWidth() <= 0 || getHeight() <= 0) return;

        float fold = clamp((180f - angle) / 92f, 0f, 1f);
        float coverProgress = 0f;
        if (coverMode && coverStartMs > 0) {
            coverProgress = clamp((SystemClock.uptimeMillis() - coverStartMs) / 430f, 0f, 1f);
        }

        shader.setFloatUniform("destSize", getWidth(), getHeight());
        shader.setFloatUniform("sourceSize", snapshot.getWidth(), snapshot.getHeight());
        shader.setFloatUniform("foldProgress", fold);
        shader.setFloatUniform("coverProgress", coverProgress);
        shader.setFloatUniform("mode", coverMode ? 1f : 0f);
        shader.setFloatUniform("movingSide", 1f); // X Fold3 Pro cover-display half; reversible in next tuning pass.
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        if (coverMode && coverProgress < 1f) {
            postInvalidateOnAnimation();
        }
    }

    static boolean isCoverAspect(int w, int h) {
        if (w <= 0 || h <= 0) return false;
        float shortSide = Math.min(w, h);
        float longSide = Math.max(w, h);
        return shortSide / longSide < 0.62f;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
