package lab.xfold.duo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Locale;

public class DuoEffectService extends Service implements SensorEventListener, DisplayManager.DisplayListener {
    static final String ACTION_START = "lab.xfold.duo.START";
    static final String ACTION_STOP = "lab.xfold.duo.STOP";
    static final String ACTION_DEMO_CLOSE = "lab.xfold.duo.DEMO_CLOSE";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    private enum Phase { IDLE, FOLDING, COVER_REVEAL, OPENING, DEMO }

    private static final int NOTIFICATION_ID = 7002;
    private static final String CHANNEL_ID = "duo_effect";
    private static final float DEFAULT_OPEN_REFERENCE = 175f;
    /** A fold needs the hinge to have been at least this open since the last effect. */
    private static final float ARM_MIN_ANGLE = 140f;
    private static final float TRIGGER_DROP_DEG = 3f;
    private static final long FRAME_THROTTLE_MS = 90L;
    /** Frames composed right after an overlay hides may still contain it. */
    private static final long CAPTURE_SETTLE_MS = 160L;
    private static final long FOLD_STALL_MS = 900L;
    private static final long OPEN_STALL_MS = 1200L;
    private static final long PHASE_TIMEOUT_MS = 8000L;
    private static final long COVER_REVEAL_MS = 700L;
    private static final float HANDOVER_STRENGTH = 0.7f;
    private static final float COVER_START_STRENGTH = 0.85f;
    private static final int LIVE_BLUR_DP = 26;

    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private HandlerThread captureThread;
    private Handler captureHandler;

    private Context overlayContext;
    private WindowManager windowManager;
    private DuoOverlayView overlayView;
    private WindowManager.LayoutParams overlayLp;
    private boolean overlayAdded = false;
    private OpenVeilView veilView;
    private WindowManager.LayoutParams veilLp;
    private boolean veilAdded = false;
    private boolean coverScreen = false;

    private Phase phase = Phase.IDLE;
    private long phaseStartMs = 0L;
    private volatile boolean capturePaused = false;
    private volatile long captureResumeAtMs = 0L;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int captureWidth;
    private int captureHeight;
    private Bitmap latestFrame;
    private long latestFrameMs = 0L;
    private Bitmap frozenFrame;
    private long lastFrameDecodeMs = 0L;
    private long lastCaptureErrorLogMs = 0L;
    private int framesDecoded = 0;
    private byte[] packedRows;

    private SensorManager sensorManager;
    private Sensor hingeSensor;
    private Float firstRaw;
    private float smoothedAngle = DEFAULT_OPEN_REFERENCE;
    private boolean hasAngle = false;
    private float peakAngle = 0f;
    private float foldReferenceAngle = DEFAULT_OPEN_REFERENCE;
    private float openReferenceAngle = DEFAULT_OPEN_REFERENCE;
    private float stallAnchorAngle = DEFAULT_OPEN_REFERENCE;
    private long lastMoveMs = 0L;

    private DisplayManager displayManager;
    private ValueAnimator phaseAnimator;
    private boolean revealWaiting = false;
    private static final float HINGE_STEP_DEG = 5f;
    private static final int BLUR_STRIPS = 6;
    private static final float CLOSED_DEG = 6f;
    private static final float OPEN_START_DEG = 12f;
    private static final long OPEN_FRAME_MAX_AGE_MS = 10 * 60 * 1000L;
    private boolean closedSeen = false;
    private boolean openingProjected = false;
    private boolean coverByAngle = false;
    private final View[] blurStrips = new View[BLUR_STRIPS];
    private final WindowManager.LayoutParams[] blurStripLps = new WindowManager.LayoutParams[BLUR_STRIPS];
    private float lastStepDeg = DEFAULT_OPEN_REFERENCE;
    private long lastStepMs = 0L;
    private float angleVelocity = 0f;
    private float displayAngle = DEFAULT_OPEN_REFERENCE;
    private boolean frameLoopRunning = false;
    private long lastFrameNanos = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            overlayContext = createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        } catch (Throwable t) {
            overlayContext = this;
        }
        windowManager = (WindowManager) overlayContext.getSystemService(WINDOW_SERVICE);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        hingeSensor = SensorUtil.findBestHingeSensor(sensorManager);

        captureThread = new HandlerThread("DuoCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        createOverlayView();
        coverScreen = screenIsCover();
        if (displayManager != null) displayManager.registerDisplayListener(this, main);
        if (sensorManager != null && hingeSensor != null) {
            sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_FASTEST, main);
        }
        DuoLog.add("service created · hinge=" + SensorUtil.describe(hingeSensor)
                + " · cover=" + coverScreen
                + " · shader=" + (overlayView.isShaderReady() ? "OK" : "FAILED " + overlayView.shaderError())
                + " · crossWindowBlur=" + crossWindowBlurEnabled());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_DEMO_CLOSE.equals(action)) {
            runManualDemo();
            return projection != null ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            startForegroundCompat();
            if (projection == null) {
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
                if (resultCode != 0 && resultData != null) {
                    startProjection(resultCode, resultData);
                } else {
                    toast("屏幕捕获授权数据为空，服务未启动");
                    stopSelf();
                }
            }
            return START_STICKY;
        }
        return projection != null ? START_STICKY : START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        createNotificationChannel();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("X Fold Duo Effect v0.6 正在待命")
                .setContentText("合拢：内屏投影 + 外屏由模糊到清晰；展开：内屏由暗到亮")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Duo Effect",
                NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Keeps the fold transition effect ready while screen capture permission is active.");
        nm.createNotificationChannel(c);
    }

    private void startProjection(int resultCode, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) { stopSelf(); return; }
        try {
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) { stopSelf(); return; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(() -> stopSelf()); }
                @Override public void onCapturedContentResize(int width, int height) {
                    main.postDelayed(DuoEffectService.this::resizeCaptureToCurrentScreen, 60);
                }
            }, captureHandler);
            createVirtualDisplayForCurrentScreen();
            toast("Duo 服务已启动，正在缓存当前屏幕");
        } catch (SecurityException e) {
            toast("MediaProjection 启动失败：" + e.getClass().getSimpleName());
            stopSelf();
        }
    }

    // ---------------------------------------------------------------- windows

    private void createOverlayView() {
        overlayView = new DuoOverlayView(overlayContext);
        overlayView.setSizeModeListener(this::onOverlaySized);
        veilView = new OpenVeilView(overlayContext);
    }

    /**
     * @param passTouches Android 12+ caps a FLAG_NOT_TOUCHABLE overlay at 80% opacity, which lets
     *     the live app show through the frozen frame. The full-screen overlay is therefore
     *     touchable; it only exists on screen while an effect runs (a GONE root hides the window
     *     and its input). The opening veil is mostly transparent and keeps passing touches.
     */
    private WindowManager.LayoutParams baseOverlayParams(int width, boolean passTouches) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (passTouches) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // Cover the status and navigation bar areas too, so the frame lines up 1:1 with the display.
        lp.setFitInsetsTypes(0);
        lp.setTitle("XFoldDuo");
        return lp;
    }

    private boolean canAddOverlays() {
        if (windowManager == null) {
            toast("叠层失败：WindowManager 不可用");
            return false;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("叠层失败：没有悬浮窗权限");
            return false;
        }
        return true;
    }

    private boolean ensureOverlayAdded() {
        if (overlayAdded) return true;
        if (!canAddOverlays()) return false;
        overlayLp = baseOverlayParams(WindowManager.LayoutParams.MATCH_PARENT, false);
        try {
            windowManager.addView(overlayView, overlayLp);
            overlayView.setVisibility(View.GONE);
            overlayAdded = true;
            return true;
        } catch (Throwable t) {
            toast("叠层 addView 失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    private void setOverlayBlurBehind(int radius) {
        if (!overlayAdded || overlayLp == null) return;
        boolean on = radius > 0;
        boolean wasOn = (overlayLp.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0;
        if (on == wasOn && overlayLp.getBlurBehindRadius() == radius) return;
        if (on) overlayLp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        else overlayLp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        overlayLp.setBlurBehindRadius(radius);
        try { windowManager.updateViewLayout(overlayView, overlayLp); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- phases

    private void setPhase(Phase next) {
        if (phase == next) return;
        phase = next;
        phaseStartMs = SystemClock.uptimeMillis();
        lastMoveMs = phaseStartMs;
        stallAnchorAngle = smoothedAngle;
        capturePaused = next != Phase.IDLE;
        main.removeCallbacks(watchdog);
        if (next != Phase.IDLE) main.postDelayed(watchdog, 200);
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            if (now - phaseStartMs > PHASE_TIMEOUT_MS) {
                DuoLog.add("timeout in " + phase);
                endEffect();
                return;
            }
            // Stopping half-way (tent / laptop posture) must not leave a frozen frame on screen.
            if (phase == Phase.FOLDING && now - lastMoveMs > FOLD_STALL_MS) {
                DuoLog.add(String.format(Locale.US, "fold stalled at %.1f°, fading out", smoothedAngle));
                fadeOutAndEnd();
                return;
            }
            if (phase == Phase.OPENING && now - lastMoveMs > OPEN_STALL_MS) {
                DuoLog.add(String.format(Locale.US, "opening stalled at %.1f°", smoothedAngle));
                endEffect();
                return;
            }
            if (phase != Phase.IDLE) main.postDelayed(this, 200);
        }
    };

    private void startFold(Bitmap frame, float angle, Phase as) {
        // Every fold starts flat at its own reference angle.
        if (!showFrozenFrame(frame, angle, 0f, false)) return;
        setPhase(as);
        if (as == Phase.FOLDING) ensureFrameLoop();
    }

    private boolean showFrozenFrame(Bitmap frame, float angle, float foldRadians, boolean cover) {
        if (frame == null || !ensureOverlayAdded()) return false;
        cancelPhaseAnimator();
        if (frozenFrame != frame) releaseFrozenFrame();
        frozenFrame = frame;
        overlayView.setSnapshot(frame);
        overlayView.setMovingRight(DuoPrefs.movingRight(this));
        overlayView.setCoverMode(cover);
        overlayView.setCoverStrength(cover ? 0f : COVER_START_STRENGTH);
        displayAngle = angle;
        overlayView.setFold(foldRadians);
        overlayView.setAlpha(cover ? 0f : 1f);
        overlayView.setVisibility(View.VISIBLE);
        return true;
    }

    private boolean usableInnerFrame(Bitmap frame) {
        return frame != null && !frame.isRecycled()
                && !DuoOverlayView.isCoverAspect(frame.getWidth(), frame.getHeight())
                && SystemClock.uptimeMillis() - latestFrameMs < OPEN_FRAME_MAX_AGE_MS;
    }

    /**
     * The cover screen shows the half of the frame it now lies over, and its blur follows the
     * hinge like the iPhone Duo cover screen: strongest at 90°, sharp when closed. So when vivo
     * switches panels near closed the picture is already almost sharp and continues the right
     * half seamlessly; when the cover lights early (handoff at ~90°) it resolves while closing.
     * Once closed, the frozen half cross-fades into the live cover layout.
     */
    private void startCoverReveal() {
        setPhase(Phase.COVER_REVEAL);
        cancelPhaseAnimator();
        coverByAngle = true;
        overlayView.setCoverMode(true);
        overlayView.setCoverStrength(coverStrengthAt(displayAngle));
        setOverlayBlurBehind(Math.round(dp(LIVE_BLUR_DP) * coverStrengthAt(displayAngle)));
        DuoLog.add(String.format(Locale.US, "cover screen active at %.1f° → reveal follows hinge", smoothedAngle));
        ensureFrameLoop();
    }

    private float coverStrengthAt(float angle) {
        return smoothstep(0f, 1f, Math.max(0f, Math.min(1f, angle / 90f)));
    }

    /** Hinge closed (or stopped): hand over to the live cover UI once the window is cover-sized. */
    private void finishCoverReveal() {
        coverByAngle = false;
        if (DuoOverlayView.isCoverAspect(overlayView.getWidth(), overlayView.getHeight())) {
            runCoverFinishAnimation();
        } else {
            revealWaiting = true;
            main.postDelayed(revealFallback, 400);
        }
    }

    private final Runnable revealFallback = () -> {
        if (revealWaiting && phase == Phase.COVER_REVEAL) {
            DuoLog.add("overlay not resized to cover within 400 ms; finishing anyway");
            runCoverFinishAnimation();
        }
    };

    private void onOverlaySized(boolean cover) {
        if (cover && revealWaiting && phase == Phase.COVER_REVEAL) runCoverFinishAnimation();
        onScreenModeSignal(cover);
    }

    private void runCoverFinishAnimation() {
        revealWaiting = false;
        main.removeCallbacks(revealFallback);
        int liveBlur = dp(LIVE_BLUR_DP);
        float startStrength = coverStrengthAt(displayAngle);
        cancelPhaseAnimator();
        phaseAnimator = ValueAnimator.ofFloat(0f, 1f);
        phaseAnimator.setDuration(COVER_REVEAL_MS);
        phaseAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Android lays the app out again for the cover, so the frozen right half and the live
            // cover UI never line up. Swap them while both are out of focus: the frozen half blurs
            // out, the live UI takes over at peak blur and then comes into focus.
            float blurOut = smoothstep(0f, 0.4f, t);
            overlayView.setCoverStrength(startStrength + (HANDOVER_STRENGTH - startStrength) * blurOut);
            overlayView.setAlpha(1f - smoothstep(0.3f, 0.55f, t));
            float liveBlur01 = smoothstep(0.1f, 0.35f, t) * (1f - smoothstep(0.55f, 1f, t));
            setOverlayBlurBehind(Math.round(liveBlur * 1.6f * liveBlur01));
        });
        phaseAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) { if (!cancelled) endEffect(); }
        });
        phaseAnimator.start();
    }

    private void startOpening() {
        if (!canAddOverlays()) return;
        if (!coverScreen && displayRotation() != Surface.ROTATION_0) {
            DuoLog.add("opening skipped: inner screen is rotated");
            return;
        }
        // Mirror of the fold: the last inner frame (usually the same app) is projected and comes
        // into focus as the hinge opens. Live content cannot be used because capturing it would
        // also capture this overlay.
        Bitmap frame = latestFrame;
        if (usableInnerFrame(frame)
                && showFrozenFrame(frame, smoothedAngle, foldRadians(smoothedAngle), coverScreen)) {
            openingProjected = true;
            DuoLog.add(String.format(Locale.US, "opening at %.1f° on %s screen → projected frame (age %d ms)",
                    smoothedAngle, coverScreen ? "cover" : "inner", SystemClock.uptimeMillis() - latestFrameMs));
            setPhase(Phase.OPENING);
            ensureFrameLoop();
            return;
        }
        if (coverScreen) return; // no frame yet; the blur veil starts once the inner screen is on
        Point size = screenSize();
        boolean right = DuoPrefs.movingRight(this);
        int half = Math.max(1, size.x / 2);
        float strength = openStrength(smoothedAngle);
        // Cross-window blur is uniform per window, so the moving half is split into strips whose
        // radius grows away from the hinge, approximating the progressive blur of the fold shader.
        int stripWidth = (half + BLUR_STRIPS - 1) / BLUR_STRIPS;
        for (int i = 0; i < BLUR_STRIPS; i++) {
            WindowManager.LayoutParams lp = baseOverlayParams(stripWidth, true);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = right ? half + i * stripWidth : half - (i + 1) * stripWidth;
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(stripRadius(i, strength));
            View strip = new View(overlayContext);
            try {
                windowManager.addView(strip, lp);
                blurStrips[i] = strip;
                blurStripLps[i] = lp;
            } catch (Throwable t) {
                DuoLog.add("blur strip addView failed: " + t.getClass().getSimpleName());
            }
        }
        // The darkening veil goes on top so it is not blurred itself.
        veilLp = baseOverlayParams(half, true);
        veilLp.gravity = Gravity.TOP | (right ? Gravity.RIGHT : Gravity.LEFT);
        veilView.setMovingRight(right);
        veilView.setStrength(strength);
        try {
            windowManager.addView(veilView, veilLp);
            veilAdded = true;
        } catch (Throwable t) {
            DuoLog.add("veil addView failed: " + t.getClass().getSimpleName());
            removeBlurStrips();
            return;
        }
        DuoLog.add(String.format(Locale.US, "inner screen active at %.1f° → opening veil", smoothedAngle));
        displayAngle = smoothedAngle;
        setPhase(Phase.OPENING);
        ensureFrameLoop();
    }

    private float openStrength(float angle) {
        return smoothstep(0f, 1f, Math.min(1f, foldRadians(angle) / (float) (Math.PI / 2)));
    }

    /** Same curve as the fold shader: maxBlur · motion · edge^1.35, sampled at the strip centre. */
    private int stripRadius(int index, float strength) {
        float edge = (index + 0.5f) / BLUR_STRIPS;
        return Math.round(dp(LIVE_BLUR_DP) * 2.4f * strength * (float) Math.pow(edge, 1.35));
    }

    private void updateBlurStrips(float strength) {
        for (int i = 0; i < BLUR_STRIPS; i++) {
            View strip = blurStrips[i];
            WindowManager.LayoutParams lp = blurStripLps[i];
            if (strip == null || lp == null) continue;
            int radius = stripRadius(i, strength);
            if (radius == lp.getBlurBehindRadius()) continue;
            lp.setBlurBehindRadius(radius);
            try { windowManager.updateViewLayout(strip, lp); } catch (Throwable ignored) { }
        }
    }

    private void removeBlurStrips() {
        for (int i = 0; i < BLUR_STRIPS; i++) {
            if (blurStrips[i] != null) {
                try { windowManager.removeViewImmediate(blurStrips[i]); } catch (Throwable ignored) { }
            }
            blurStrips[i] = null;
            blurStripLps[i] = null;
        }
    }

    /**
     * The vivo hinge sensor reports in 5° steps, roughly every 0.2 s while moving. Drawing those
     * steps directly makes the fold stutter, so each display frame extrapolates the last step with
     * the measured angular velocity (at most one step ahead) and eases towards that.
     */
    private float predictedAngle(long now) {
        long since = now - lastStepMs;
        if (since > 400L) return lastStepDeg;
        float ahead = Math.max(-HINGE_STEP_DEG, Math.min(HINGE_STEP_DEG, angleVelocity * since));
        return Math.max(0f, Math.min(180f, lastStepDeg + ahead));
    }

    private void onHingeStep(float deg, long now) {
        if (deg == lastStepDeg) return;
        // Separate tag so a host-side tool (early handoff experiment) can follow the hinge live.
        android.util.Log.i("XFoldDuoHinge", String.format(Locale.US, "deg=%.1f phase=%s", deg, phase));
        long dt = now - lastStepMs;
        float v = dt > 0 && dt < 600 ? (deg - lastStepDeg) / dt : 0f;
        angleVelocity = 0.5f * angleVelocity + 0.5f * v;
        lastStepDeg = deg;
        lastStepMs = now;
    }

    private void ensureFrameLoop() {
        if (frameLoopRunning) return;
        frameLoopRunning = true;
        lastFrameNanos = 0L;
        Choreographer.getInstance().postFrameCallback(frameLoop);
    }

    private final Choreographer.FrameCallback frameLoop = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (phase != Phase.FOLDING && phase != Phase.OPENING
                    && !(phase == Phase.COVER_REVEAL && coverByAngle)) {
                frameLoopRunning = false;
                return;
            }
            float dt = lastFrameNanos == 0L ? 0.016f : (frameTimeNanos - lastFrameNanos) / 1e9f;
            lastFrameNanos = frameTimeNanos;
            long now = SystemClock.uptimeMillis();
            float target = predictedAngle(now);
            // While the hinge is still moving, never step back against its direction: the next
            // 5° reading corrects any overshoot (same rule as duo-open's HingeTracker).
            if (now - lastStepMs < 400L) {
                if (angleVelocity < 0f) target = Math.min(target, displayAngle);
                else if (angleVelocity > 0f) target = Math.max(target, displayAngle);
            }
            displayAngle += (target - displayAngle) * (1f - (float) Math.exp(-dt / 0.035f));
            if (phase == Phase.FOLDING) {
                overlayView.setFold(foldRadians(displayAngle));
            } else if (phase == Phase.COVER_REVEAL) {
                float strength = coverStrengthAt(displayAngle);
                overlayView.setCoverStrength(strength);
                setOverlayBlurBehind(Math.round(dp(LIVE_BLUR_DP) * strength));
                // The loop's own check stops it on the next frame once this clears coverByAngle.
                if (displayAngle <= CLOSED_DEG + 1f || now - lastMoveMs > OPEN_STALL_MS) {
                    finishCoverReveal();
                }
            } else if (openingProjected) {
                if (overlayView.isCoverMode()) {
                    // Still on the cover: its picture loses focus as the hinge opens.
                    overlayView.setCoverStrength(COVER_START_STRENGTH
                            * smoothstep(OPEN_START_DEG, 70f, displayAngle));
                    overlayView.setAlpha(smoothstep(OPEN_START_DEG, OPEN_START_DEG + 18f, displayAngle));
                } else {
                    overlayView.setFold(foldRadians(displayAngle));
                    overlayView.setAlpha(1f - smoothstep(openReferenceAngle - 40f,
                            openReferenceAngle - HINGE_STEP_DEG, displayAngle));
                }
            } else if (veilAdded) {
                float strength = openStrength(displayAngle);
                veilView.setStrength(strength);
                updateBlurStrips(strength);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void fadeOutAndEnd() {
        cancelPhaseAnimator();
        setPhase(Phase.DEMO); // freeze sensor handling while the fade runs
        phaseAnimator = ValueAnimator.ofFloat(overlayView.getAlpha(), 0f);
        phaseAnimator.setDuration(180);
        phaseAnimator.addUpdateListener(a -> overlayView.setAlpha((float) a.getAnimatedValue()));
        phaseAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) { if (!cancelled) endEffect(); }
        });
        phaseAnimator.start();
    }

    private void endEffect() {
        cancelPhaseAnimator();
        openingProjected = false;
        coverByAngle = false;
        revealWaiting = false;
        main.removeCallbacks(revealFallback);
        if (overlayAdded) {
            overlayView.setVisibility(View.GONE);
            overlayView.setAlpha(1f);
            overlayView.setCoverMode(false);
            overlayView.setSnapshot(null);
            setOverlayBlurBehind(0);
        }
        releaseFrozenFrame();
        if (veilAdded) {
            try { windowManager.removeViewImmediate(veilView); } catch (Throwable ignored) { }
            veilAdded = false;
        }
        removeBlurStrips();
        captureResumeAtMs = SystemClock.uptimeMillis() + CAPTURE_SETTLE_MS;
        setPhase(Phase.IDLE);
    }

    private void cancelPhaseAnimator() {
        if (phaseAnimator != null) phaseAnimator.cancel();
        phaseAnimator = null;
    }

    // ---------------------------------------------------------------- screen mode

    /** Either the display listener or the overlay's own resize may notice the panel switch first. */
    private void onScreenModeSignal(boolean cover) {
        if (cover == coverScreen) return;
        coverScreen = cover;
        DuoLog.add("screen → " + (cover ? "cover" : "inner") + " · phase=" + phase
                + String.format(Locale.US, " · angle=%.1f°", smoothedAngle));
        if (cover) {
            if (phase == Phase.FOLDING) startCoverReveal();
            else if (phase == Phase.OPENING) endEffect();
        } else {
            if (phase == Phase.COVER_REVEAL) endEffect();
            if (phase == Phase.OPENING && openingProjected) {
                // Same overlay, now on the inner panel: continue as the reverse projection.
                overlayView.setCoverMode(false);
                overlayView.setFold(foldRadians(displayAngle));
                overlayView.setAlpha(1f);
                return;
            }
            if (phase == Phase.IDLE && DuoPrefs.openingEnabled(this)) {
                // Give the inner display a moment to report its new size before measuring it.
                main.postDelayed(() -> {
                    if (phase == Phase.IDLE && !coverScreen && hasAngle
                            && smoothedAngle < openReferenceAngle - 10f) {
                        startOpening();
                    }
                }, 80);
            }
        }
    }

    private Display defaultDisplay() {
        return displayManager == null ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
    }

    @SuppressWarnings("deprecation")
    private Point screenSize() {
        Point p = new Point(0, 0);
        Display d = defaultDisplay();
        if (d != null) d.getRealSize(p);
        return p;
    }

    private boolean screenIsCover() {
        Point p = screenSize();
        return DuoOverlayView.isCoverAspect(p.x, p.y);
    }

    private int displayRotation() {
        Display d = defaultDisplay();
        return d == null ? Surface.ROTATION_0 : d.getRotation();
    }

    private boolean crossWindowBlurEnabled() {
        try {
            return windowManager != null && windowManager.isCrossWindowBlurEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- demo

    /**
     * The capture only delivers a frame when the screen changes, so the cached frame stays cached
     * while an effect borrows it: folding twice over an unchanged screen still has a frame. A
     * bitmap is recycled only once neither the cache nor the overlay holds it.
     */
    private void releaseFrozenFrame() {
        if (frozenFrame != null && frozenFrame != latestFrame && !frozenFrame.isRecycled()) {
            frozenFrame.recycle();
        }
        frozenFrame = null;
    }

    private void runManualDemo() {
        if (phase != Phase.IDLE) endEffect();
        Bitmap frame = latestFrame;
        boolean live = frame != null && !frame.isRecycled();
        if (!live) frame = makeDiagnosticBitmap("OVERLAY OK\nNO LIVE CAPTURE FRAME");
        startFold(frame, openReferenceAngle, Phase.DEMO);
        if (phase != Phase.DEMO) return;
        toast(live
                ? "手动测试：实时屏幕帧 OK · 叠层 OK · Shader " + (overlayView.isShaderReady() ? "OK" : "FALLBACK")
                : "手动测试：叠层已出现，但还没收到实时屏幕帧");
        // Fold to nearly closed and back, so both directions of the projection can be checked flat.
        phaseAnimator = ValueAnimator.ofFloat(0f, 1f, 1f, 0f);
        phaseAnimator.setDuration(3000);
        phaseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        phaseAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            overlayView.setFold(foldRadians(openReferenceAngle + (12f - openReferenceAngle) * t));
        });
        phaseAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) { if (!cancelled) endEffect(); }
        });
        phaseAnimator.start();
    }

    private Bitmap makeDiagnosticBitmap(String message) {
        Point screen = screenSize();
        int w = captureWidth > 0 ? captureWidth : Math.max(1080, screen.x);
        int h = captureHeight > 0 ? captureHeight : Math.max(1080, screen.y);
        Bitmap b = Bitmap.createBitmap(Math.max(320, w), Math.max(320, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(20, 28, 64));
        c.drawRect(0, 0, b.getWidth() / 2f, b.getHeight(), p);
        p.setColor(Color.rgb(190, 35, 110));
        c.drawRect(b.getWidth() / 2f, 0, b.getWidth(), b.getHeight(), p);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(Math.max(28f, b.getWidth() * 0.045f));
        float y = b.getHeight() * 0.42f;
        for (String line : message.split("\\n")) {
            c.drawText(line, b.getWidth() / 2f, y, p);
            y += p.getTextSize() * 1.35f;
        }
        return b;
    }

    // ---------------------------------------------------------------- capture

    private void createVirtualDisplayForCurrentScreen() {
        int[] size = currentCaptureSize();
        captureWidth = size[0];
        captureHeight = size[1];
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        int densityDpi = getResources().getConfiguration().densityDpi;
        try {
            virtualDisplay = projection.createVirtualDisplay(
                    "XFoldDuoCapture", captureWidth, captureHeight, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, captureHandler);
            DuoLog.add("capture display " + captureWidth + "x" + captureHeight);
        } catch (Throwable t) {
            DuoLog.add("VirtualDisplay failed: " + t);
            toast("VirtualDisplay 创建失败：" + t.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void resizeCaptureToCurrentScreen() {
        if (virtualDisplay == null || projection == null) return;
        int[] size = currentCaptureSize();
        if (size[0] == captureWidth && size[1] == captureHeight) return;
        ImageReader next = ImageReader.newInstance(size[0], size[1], PixelFormat.RGBA_8888, 3);
        next.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        try {
            virtualDisplay.setSurface(null);
            ImageReader old = imageReader;
            imageReader = next;
            captureWidth = size[0];
            captureHeight = size[1];
            virtualDisplay.resize(captureWidth, captureHeight, getResources().getConfiguration().densityDpi);
            virtualDisplay.setSurface(imageReader.getSurface());
            if (old != null) old.close();
        } catch (Throwable t) {
            next.close();
        }
    }

    private int[] currentCaptureSize() {
        int w = 1080, h = 1080;
        if (windowManager != null) {
            try {
                Rect b = windowManager.getCurrentWindowMetrics().getBounds();
                w = Math.max(1, b.width());
                h = Math.max(1, b.height());
            } catch (Throwable ignored) { }
        }
        int longSide = Math.max(w, h);
        float scale = longSide > 1440 ? 1440f / longSide : 1f;
        int cw = Math.max(320, Math.round(w * scale));
        int ch = Math.max(320, Math.round(h * scale));
        cw &= ~1;
        ch &= ~1;
        return new int[]{cw, ch};
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.uptimeMillis();
            if (capturePaused || now < captureResumeAtMs) return;
            if (now - lastFrameDecodeMs < FRAME_THROTTLE_MS) return;
            lastFrameDecodeMs = now;
            Bitmap bmp = imageToBitmap(image);
            if (bmp == null) return;
            main.post(() -> {
                if (capturePaused) {
                    if (!bmp.isRecycled()) bmp.recycle();
                    return;
                }
                // Only inner-screen frames can seed a fold; keep the last one across cover use.
                if (DuoOverlayView.isCoverAspect(bmp.getWidth(), bmp.getHeight())) {
                    if (!bmp.isRecycled()) bmp.recycle();
                    return;
                }
                Bitmap old = latestFrame;
                latestFrame = bmp;
                latestFrameMs = SystemClock.uptimeMillis();
                if (old != null && old != bmp && old != frozenFrame && !old.isRecycled()) old.recycle();
            });
            if (framesDecoded++ == 0) {
                DuoLog.add("first capture frame " + bmp.getWidth() + "x" + bmp.getHeight());
            }
        } catch (Throwable t) {
            long now = SystemClock.uptimeMillis();
            if (now - lastCaptureErrorLogMs > 5000) {
                lastCaptureErrorLogMs = now;
                DuoLog.add("capture frame failed: " + t);
            }
        } finally {
            if (image != null) image.close();
        }
    }

    /**
     * Copies rows one by one: the plane's rows may be padded, and the last row usually is not, so
     * the buffer is shorter than rowStride * height and cannot be copied into a padded bitmap.
     */
    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        Image.Plane p = planes[0];
        ByteBuffer buffer = p.getBuffer();
        int w = image.getWidth();
        int h = image.getHeight();
        int rowBytes = w * p.getPixelStride();
        int rowStride = p.getRowStride();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        if (rowStride == rowBytes) {
            bmp.copyPixelsFromBuffer(buffer);
            return bmp;
        }
        if (packedRows == null || packedRows.length != rowBytes * h) packedRows = new byte[rowBytes * h];
        for (int y = 0; y < h; y++) {
            buffer.position(y * rowStride);
            buffer.get(packedRows, y * rowBytes, rowBytes);
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(packedRows));
        return bmp;
    }

    // ---------------------------------------------------------------- hinge

    /**
     * How far the moving half has turned away from flat. A fold is measured from the angle it
     * started at (the hinge may never read fully open), anything else from the device's "open".
     */
    private float foldRadians(float hingeDegrees) {
        float reference = phase == Phase.FOLDING ? foldReferenceAngle : openReferenceAngle;
        float fold = (reference - hingeDegrees) / reference;
        return (float) Math.PI * Math.max(0f, Math.min(1f, fold));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (hingeSensor == null || event.sensor != hingeSensor || event.values.length == 0) return;
        float raw = event.values[0];
        if (firstRaw == null && Float.isFinite(raw)) firstRaw = raw;
        float deg = SensorUtil.normalizeHingeDegrees(hingeSensor, raw, firstRaw);
        if (!Float.isFinite(deg)) return;

        if (!hasAngle) smoothedAngle = deg;
        else smoothedAngle += (deg - smoothedAngle) * 0.62f;
        hasAngle = true;
        onHingeStep(deg, SystemClock.uptimeMillis());

        if (Math.abs(smoothedAngle - stallAnchorAngle) > 0.8f) {
            stallAnchorAngle = smoothedAngle;
            lastMoveMs = SystemClock.uptimeMillis();
        }

        if (phase == Phase.IDLE && deg > 160f) {
            openReferenceAngle = Math.min(180f, Math.max(openReferenceAngle, deg));
        }

        // A fold starts from wherever the hinge peaked, not only from fully open: with 5° steps
        // and quick gestures the hinge often never reads the open reference before closing again.
        // Raw readings are used so the very first step down triggers.
        if (phase == Phase.IDLE || phase == Phase.OPENING) peakAngle = Math.max(peakAngle, deg);

        if (coverScreen && deg <= CLOSED_DEG) closedSeen = true;

        switch (phase) {
            case IDLE:
                if (coverScreen && closedSeen && deg >= OPEN_START_DEG && DuoPrefs.openingEnabled(this)) {
                    closedSeen = false;
                    startOpening();
                    break;
                }
                if (peakAngle >= ARM_MIN_ANGLE && deg <= peakAngle - TRIGGER_DROP_DEG
                        && !coverScreen && deg > 28f) {
                    foldReferenceAngle = peakAngle;
                    peakAngle = 0f;
                    triggerFold();
                }
                break;
            case FOLDING:
                if (deg >= foldReferenceAngle) {
                    // Opened back up before the cover screen took over.
                    endEffect();
                    peakAngle = deg;
                } else {
                    ensureFrameLoop();
                }
                break;
            case OPENING:
                if (coverScreen && deg <= CLOSED_DEG) {
                    endEffect();
                } else if (deg >= openReferenceAngle - HINGE_STEP_DEG) {
                    endEffect();
                } else if (peakAngle >= ARM_MIN_ANGLE && deg <= peakAngle - TRIGGER_DROP_DEG) {
                    // Closed again before the opening finished: hand straight over to a fold.
                    DuoLog.add(String.format(Locale.US, "opening reversed at %.1f°", deg));
                    endEffect();
                    foldReferenceAngle = peakAngle;
                    peakAngle = 0f;
                    triggerFold();
                } else {
                    ensureFrameLoop();
                }
                break;
            default:
                break;
        }
    }

    private void triggerFold() {
        if (displayRotation() != Surface.ROTATION_0) {
            DuoLog.add("fold skipped: inner screen is rotated (only natural orientation is supported)");
            return;
        }
        Bitmap frame = latestFrame;
        if (frame == null || frame.isRecycled()) {
            DuoLog.add("fold skipped: no cached frame (is screen capture running?)");
            return;
        }
        if (DuoOverlayView.isCoverAspect(frame.getWidth(), frame.getHeight())) {
            DuoLog.add("fold skipped: cached frame is from the cover screen");
            return;
        }
        DuoLog.add(String.format(Locale.US, "fold triggered at %.1f° from peak %.1f° · frame age %d ms",
                smoothedAngle, foldReferenceAngle, SystemClock.uptimeMillis() - latestFrameMs));
        startFold(frame, foldReferenceAngle, Phase.FOLDING);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onDisplayAdded(int displayId) { main.postDelayed(this::resizeCaptureToCurrentScreen, 60); }
    @Override public void onDisplayRemoved(int displayId) { main.postDelayed(this::resizeCaptureToCurrentScreen, 60); }
    @Override public void onDisplayChanged(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) onScreenModeSignal(screenIsCover());
        main.postDelayed(this::resizeCaptureToCurrentScreen, 60);
    }

    // ---------------------------------------------------------------- misc

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String text) {
        main.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        cancelPhaseAnimator();
        main.removeCallbacks(watchdog);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (displayManager != null) displayManager.unregisterDisplayListener(this);
        if (overlayAdded && windowManager != null) {
            try { windowManager.removeViewImmediate(overlayView); } catch (Throwable ignored) { }
        }
        overlayAdded = false;
        if (veilAdded && windowManager != null) {
            try { windowManager.removeViewImmediate(veilView); } catch (Throwable ignored) { }
        }
        veilAdded = false;
        removeBlurStrips();
        overlayView.release();
        if (virtualDisplay != null) virtualDisplay.release();
        virtualDisplay = null;
        if (imageReader != null) imageReader.close();
        imageReader = null;
        if (projection != null) projection.stop();
        projection = null;
        releaseFrozenFrame();
        if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
        latestFrame = null;
        if (captureThread != null) captureThread.quitSafely();
        DuoLog.add("service destroyed");
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
