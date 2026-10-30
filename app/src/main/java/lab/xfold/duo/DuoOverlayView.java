package lab.xfold.duo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/**
 * Full-screen overlay that renders one frozen frame of the inner display.
 *
 * Inner mode: the moving half shows the frame through a fixed front-view projection (the picture
 * appears to stay flat in space while the panel turns towards the viewer), with blur and darkening
 * that grow towards the panel's outer edge. Cover mode: once the logical display has switched to
 * the cover panel, the half of the frame that the cover now lies over is shown aligned to the
 * hinge-side edge and resolves from blurred to sharp.
 */
final class DuoOverlayView extends View {
    interface SizeModeListener { void onCoverModeChanged(boolean cover); }

    static final String AGSL =
            "uniform shader content;\n" +
            "uniform shader level1;\n" +
            "uniform shader level2;\n" +
            "uniform shader level3;\n" +
            "uniform float2 destSize;\n" +
            "uniform float2 sourceSize;\n" +
            "uniform float2 scale1;\n" +
            "uniform float2 scale2;\n" +
            "uniform float2 scale3;\n" +
            "uniform float fold;\n" +
            "uniform float coverStrength;\n" +
            "uniform float mode;\n" +
            "uniform float movingSide;\n" +
            "uniform float eyeDistance;\n" +
            "uniform float maxBlur;\n" +
            "\n" +
            // Out-of-frame samples are black, so blur spreads the picture into a dark margin.
            "float inside(float2 p) {\n" +
            "  return step(0.0, p.x) * step(p.x, sourceSize.x) * step(0.0, p.y) * step(p.y, sourceSize.y);\n" +
            "}\n" +
            "\n" +
            "float4 sampleLevel(float lv, float2 p) {\n" +
            "  float2 q = clamp(p, float2(0.5), sourceSize - float2(0.5));\n" +
            "  float4 c;\n" +
            "  if (lv < 0.5) c = float4(content.eval(q));\n" +
            "  else if (lv < 1.5) c = float4(level1.eval(q * scale1));\n" +
            "  else if (lv < 2.5) c = float4(level2.eval(q * scale2));\n" +
            "  else c = float4(level3.eval(q * scale3));\n" +
            "  return c * inside(p);\n" +
            "}\n" +
            "\n" +
            "float4 tent(float lv, float2 p, float r) {\n" +
            "  float4 c = sampleLevel(lv, p) * 4.0;\n" +
            "  c += (sampleLevel(lv, p + float2(r, 0.0)) + sampleLevel(lv, p - float2(r, 0.0))\n" +
            "      + sampleLevel(lv, p + float2(0.0, r)) + sampleLevel(lv, p - float2(0.0, r))) * 2.0;\n" +
            "  c += sampleLevel(lv, p + float2(r, r)) + sampleLevel(lv, p + float2(-r, r))\n" +
            "      + sampleLevel(lv, p + float2(r, -r)) + sampleLevel(lv, p + float2(-r, -r));\n" +
            "  return c / 16.0;\n" +
            "}\n" +
            "\n" +
            // Levels 1..3 are the frame box-filtered down by 4, 16 and 64. Picking the level whose
            // texel is about r / 1.5 keeps wide blurs smooth instead of ghosting.
            "float4 blurAt(float2 p, float r) {\n" +
            "  if (r < 0.75) return sampleLevel(0.0, p);\n" +
            "  float lv = clamp(log2(r / 1.5) * 0.5, 0.0, 3.0);\n" +
            "  float lo = floor(lv);\n" +
            "  float4 a = tent(lo, p, r);\n" +
            "  float f = lv - lo;\n" +
            "  if (f < 0.02 || lo > 2.5) return a;\n" +
            "  return mix(a, tent(lo + 1.0, p, r), f);\n" +
            "}\n" +
            "\n" +
            // Dims but never blacks out: on the real phone the eye still sees the moving half.
            "float shadeFor(float strength, float edge) {\n" +
            "  return min(0.55, 1.1 * strength * pow(clamp((edge - 0.2) / 0.8, 0.0, 1.0), 1.35));\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 p) {\n" +
            "  float2 dest = max(destSize, float2(1.0));\n" +
            "  float side = movingSide > 0.5 ? 1.0 : -1.0;\n" +
            "  if (mode < 0.5) {\n" +
            "    float hinge = 0.5 * dest.x;\n" +
            "    float d = (p.x - hinge) * side;\n" +
            "    float motion = smoothstep(0.0, 1.0, clamp(fold / 1.5707963, 0.0, 1.0));\n" +
            "    float2 q = p;\n" +
            "    float edge = 0.0;\n" +
            "    if (d > 0.0) {\n" +
            // Panel point at distance d from the hinge, turned by `fold` towards an eye placed
            // eyeDistance screen widths in front of the hinge. Cast the eye ray back onto the
            // unfolded plane to find which part of the frame belongs there.
            // The study's camera sees the half edge-on at 90°; a hand-held phone is still seen at an
            // angle, so the projected turn saturates at about 70° instead of collapsing to a line.
            "      float e2 = exp(2.0 * fold / 1.2217);\n" +
            "      float turn = 1.2217 * (e2 - 1.0) / (e2 + 1.0);\n" +
            "      float c = cos(turn);\n" +
            "      float s = sin(turn);\n" +
            "      float eye = eyeDistance * dest.x;\n" +
            "      float k = eye / max(eye - d * s, 1.0);\n" +
            "      q = float2(hinge + side * d * c * k, 0.5 * dest.y + (p.y - 0.5 * dest.y) * k);\n" +
            "      edge = clamp(d / hinge, 0.0, 1.0);\n" +
            "    }\n" +
            "    float radius = maxBlur * motion * pow(edge, 1.35);\n" +
            "    float shade = shadeFor(motion, edge);\n" +
            "    float4 col = blurAt(q * (sourceSize / dest), radius);\n" +
            "    return half4(half3(col.rgb * (1.0 - shade)), 1.0);\n" +
            "  }\n" +
            // Cover mode: the cover panel now lies over the fixed half. Fill its height with the
            // frame and anchor the frame's hinge line to the cover's hinge-side edge.
            "  float scale = sourceSize.y / dest.y;\n" +
            "  float fromHinge = movingSide > 0.5 ? dest.x - p.x : p.x;\n" +
            "  float2 q = float2(0.5 * sourceSize.x - side * fromHinge * scale,\n" +
            "      0.5 * sourceSize.y + (p.y - 0.5 * dest.y) * scale);\n" +
            "  float edge = clamp(fromHinge / dest.x, 0.0, 1.0);\n" +
            "  float radius = maxBlur * coverStrength * (0.3 + 0.7 * pow(edge, 1.35));\n" +
            "  float shade = shadeFor(coverStrength, edge);\n" +
            "  float4 col = blurAt(q, radius);\n" +
            "  return half4(half3(col.rgb * (1.0 - shade)), 1.0);\n" +
            "}\n";

    /** Viewer distance in inner-screen widths; about 40 cm for a 13 cm wide inner screen. */
    private static final float EYE_DISTANCE = 3.0f;
    /** Strongest blur radius as a fraction of the frame width. */
    private static final float MAX_BLUR_FRACTION = 0.08f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RuntimeShader shader;
    private final String shaderError;
    private Bitmap snapshot;
    private Bitmap[] levels = new Bitmap[0];
    private float foldRadians = 0f;
    private boolean coverMode = false;
    private float coverStrength = 1f;
    private boolean movingRight = false;
    private SizeModeListener sizeModeListener;

    DuoOverlayView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        RuntimeShader candidate;
        String error = null;
        try {
            candidate = new RuntimeShader(AGSL);
        } catch (RuntimeException ex) {
            candidate = null;
            error = ex.getMessage();
        }
        shader = candidate;
        shaderError = error;
        if (shader != null) paint.setShader(shader);
    }

    boolean isShaderReady() { return shader != null; }

    String shaderError() { return shaderError; }

    void setSizeModeListener(SizeModeListener l) { sizeModeListener = l; }

    /** Takes ownership of the bitmap's pyramid; the caller still owns {@code bitmap}. */
    void setSnapshot(Bitmap bitmap) {
        releaseLevels();
        snapshot = bitmap;
        if (bitmap != null && shader != null) {
            levels = buildLevels(bitmap);
            shader.setInputShader("content", linearShader(bitmap));
            shader.setInputShader("level1", linearShader(levels[0]));
            shader.setInputShader("level2", linearShader(levels[1]));
            shader.setInputShader("level3", linearShader(levels[2]));
        }
        invalidate();
    }

    void setMovingRight(boolean right) {
        movingRight = right;
        invalidate();
    }

    /** @param radians how far the moving half has turned away from flat, 0..pi. */
    void setFold(float radians) {
        foldRadians = clamp(radians, 0f, (float) Math.PI);
        invalidate();
    }

    void setCoverStrength(float strength) {
        coverStrength = clamp(strength, 0f, 1f);
        invalidate();
    }

    void setCoverMode(boolean cover) {
        coverMode = cover;
        invalidate();
    }

    boolean isCoverMode() { return coverMode; }

    void release() {
        releaseLevels();
        snapshot = null;
    }

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
        if (snapshot == null || snapshot.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return;

        if (shader == null) {
            // Fail visibly instead of silently: if AGSL ever fails on a vendor build, the user still
            // sees the frozen frame and can report it.
            canvas.drawBitmap(snapshot, null, new RectF(0, 0, getWidth(), getHeight()), paint);
            return;
        }

        // Until this window has actually been resized to the cover panel, keep drawing the
        // inner-screen fold so the handoff never shows a stretched frame.
        boolean drawCover = coverMode && isCoverAspect(getWidth(), getHeight());
        shader.setFloatUniform("destSize", getWidth(), getHeight());
        shader.setFloatUniform("sourceSize", snapshot.getWidth(), snapshot.getHeight());
        setScaleUniform("scale1", levels[0]);
        setScaleUniform("scale2", levels[1]);
        setScaleUniform("scale3", levels[2]);
        shader.setFloatUniform("fold", foldRadians);
        shader.setFloatUniform("coverStrength", coverStrength);
        shader.setFloatUniform("mode", drawCover ? 1f : 0f);
        shader.setFloatUniform("movingSide", movingRight ? 1f : 0f);
        shader.setFloatUniform("eyeDistance", EYE_DISTANCE);
        shader.setFloatUniform("maxBlur", MAX_BLUR_FRACTION * snapshot.getWidth());
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }

    private void setScaleUniform(String name, Bitmap level) {
        shader.setFloatUniform(name, level.getWidth() / (float) snapshot.getWidth(),
                level.getHeight() / (float) snapshot.getHeight());
    }

    /** Successive 2x bilinear downscales are box filters, which is what the blur levels need. */
    private static Bitmap[] buildLevels(Bitmap source) {
        Bitmap[] out = new Bitmap[3];
        Bitmap current = source;
        for (int halving = 1; halving <= 6; halving++) {
            Bitmap next = Bitmap.createScaledBitmap(current,
                    Math.max(1, current.getWidth() / 2), Math.max(1, current.getHeight() / 2), true);
            // Odd halvings are intermediates; even ones are kept as levels.
            if (current != source && (halving - 1) % 2 == 1) current.recycle();
            current = next;
            if (halving % 2 == 0) out[halving / 2 - 1] = current;
        }
        return out;
    }

    private static BitmapShader linearShader(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    private void releaseLevels() {
        for (Bitmap b : levels) if (b != null && !b.isRecycled()) b.recycle();
        levels = new Bitmap[0];
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
