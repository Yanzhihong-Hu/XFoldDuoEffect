package lab.xfold.duo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * Opening effect. The inner screen shows the app's fresh layout, which cannot be captured without
 * also capturing this window, so the moving half is treated live: its window blurs whatever is
 * behind it (cross-window blur, set by the service) and this view darkens it towards the outer
 * edge with the same curve the fold shader uses.
 */
final class OpenVeilView extends View {
    private static final int STOPS = 12;

    private final Paint paint = new Paint();
    private final int[] colors = new int[STOPS];
    private final float[] positions = new float[STOPS];
    private float strength = 1f;
    private boolean movingRight = false;

    OpenVeilView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        for (int i = 0; i < STOPS; i++) positions[i] = i / (float) (STOPS - 1);
    }

    void setMovingRight(boolean right) {
        movingRight = right;
        invalidate();
    }

    void setStrength(float s) {
        s = Math.max(0f, Math.min(1f, s));
        if (Math.abs(s - strength) < 0.003f) return;
        strength = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        if (w <= 0 || getHeight() <= 0 || strength <= 0f) return;
        for (int i = 0; i < STOPS; i++) {
            float edge = positions[i];
            float shade = Math.min(0.55f, 1.1f * strength
                    * (float) Math.pow(Math.max(0f, Math.min(1f, (edge - 0.2f) / 0.8f)), 1.35));
            colors[i] = Color.argb(Math.round(255 * shade), 0, 0, 0);
        }
        // This window covers only the moving half; its hinge side is the edge facing the centre.
        float hingeX = movingRight ? 0f : w;
        float outerX = movingRight ? w : 0f;
        paint.setShader(new LinearGradient(hingeX, 0, outerX, 0, colors, positions, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, getHeight(), paint);
    }
}
