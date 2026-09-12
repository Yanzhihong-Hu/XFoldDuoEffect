package lab.xfold.duo;

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
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;

public class DuoEffectService extends Service implements SensorEventListener, DisplayManager.DisplayListener {
    static final String ACTION_START = "lab.xfold.duo.START";
    static final String ACTION_STOP = "lab.xfold.duo.STOP";
    static final String ACTION_DEMO_CLOSE = "lab.xfold.duo.DEMO_CLOSE";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final int NOTIFICATION_ID = 7002;
    private static final String CHANNEL_ID = "duo_effect";
    private static final float DEFAULT_OPEN_REFERENCE = 175f;
    private static final float MIN_ARM_ANGLE = 167f;
    private static final float TRIGGER_DELTA = 1.6f;
    private static final long FRAME_THROTTLE_MS = 90L;

    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private HandlerThread captureThread;
    private Handler captureHandler;

    private Context overlayContext;
    private WindowManager windowManager;
    private DuoOverlayView overlayView;
    private boolean overlayAdded = false;
    private boolean overlayVisible = false;
    private boolean lastCoverAspect = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int captureWidth;
    private int captureHeight;
    private Bitmap latestFrame;
    private Bitmap frozenFrame;
    private long lastFrameDecodeMs = 0L;

    private SensorManager sensorManager;
    private Sensor hingeSensor;
    private Float firstRaw;
    private float smoothedAngle = DEFAULT_OPEN_REFERENCE;
    private boolean hasAngle = false;
    private boolean foldArmed = true;
    private float openReferenceAngle = DEFAULT_OPEN_REFERENCE;

    private DisplayManager displayManager;
    private ValueAnimator demoAnimator;

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
        if (displayManager != null) displayManager.registerDisplayListener(this, main);
        if (sensorManager != null && hingeSensor != null) {
            sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_FASTEST, main);
        }
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
                .setContentTitle("X Fold Duo Effect v0.4 正在待命")
                .setContentText("实时缓存屏幕帧；vivo foldstatus 开始合拢即触发")
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

    private void createOverlayView() {
        overlayView = new DuoOverlayView(overlayContext);
        overlayView.setOpenReference(openReferenceAngle);
        overlayView.setSizeModeListener(cover -> {
            if (cover == lastCoverAspect) return;
            lastCoverAspect = cover;
            if (cover && overlayVisible && frozenFrame != null) {
                overlayView.beginCoverReveal();
                main.postDelayed(this::hideOverlay, 410);
            } else if (!cover && overlayVisible) {
                overlayView.setInnerMode();
            }
        });
    }

    private boolean ensureOverlayAdded() {
        if (overlayAdded) return true;
        if (windowManager == null) {
            toast("叠层失败：WindowManager 不可用");
            return false;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("叠层失败：没有悬浮窗权限");
            return false;
        }
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            windowManager.addView(overlayView, lp);
            overlayView.setVisibility(android.view.View.GONE);
            overlayAdded = true;
            return true;
        } catch (Throwable t) {
            toast("叠层 addView 失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    private void showOverlay(Bitmap frame, float angle) {
        if (frame == null || !ensureOverlayAdded()) return;
        if (frozenFrame != null && frozenFrame != frame && !frozenFrame.isRecycled()) {
            frozenFrame.recycle();
        }
        frozenFrame = frame;
        overlayView.setOpenReference(openReferenceAngle);
        overlayView.setSnapshot(frame);
        overlayView.setInnerMode();
        overlayView.setAngle(angle);
        overlayView.setVisibility(android.view.View.VISIBLE);
        overlayVisible = true;
    }

    private void hideOverlay() {
        overlayVisible = false;
        if (overlayAdded) overlayView.setVisibility(android.view.View.GONE);
    }

    private Bitmap detachLatestFrame() {
        Bitmap b = latestFrame;
        latestFrame = null;
        return b;
    }

    private void runManualDemo() {
        Bitmap frame = detachLatestFrame();
        boolean live = frame != null && !frame.isRecycled();
        if (!live) frame = makeDiagnosticBitmap("OVERLAY OK\nNO LIVE CAPTURE FRAME");
        showOverlay(frame, openReferenceAngle);
        if (!overlayVisible) return;
        toast(live
                ? "手动测试：实时屏幕帧 OK · 叠层 OK · Shader " + (overlayView.isShaderReady() ? "OK" : "FALLBACK")
                : "手动测试：叠层已出现，但还没收到实时屏幕帧");
        runDemoAnimation();
    }

    private Bitmap makeDiagnosticBitmap(String message) {
        int w = captureWidth > 0 ? captureWidth : 1080;
        int h = captureHeight > 0 ? captureHeight : 1080;
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
        } catch (Throwable t) {
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
            if (overlayVisible) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastFrameDecodeMs < FRAME_THROTTLE_MS) return;
            lastFrameDecodeMs = now;
            Bitmap bmp = imageToBitmap(image);
            if (bmp == null) return;
            main.post(() -> {
                if (overlayVisible) {
                    if (!bmp.isRecycled()) bmp.recycle();
                    return;
                }
                Bitmap old = latestFrame;
                latestFrame = bmp;
                if (old != null && old != bmp && !old.isRecycled()) old.recycle();
            });
        } catch (Throwable ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        Image.Plane p = planes[0];
        ByteBuffer buffer = p.getBuffer();
        int pixelStride = p.getPixelStride();
        int rowStride = p.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + Math.max(0, rowPadding / Math.max(1, pixelStride));
        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == image.getWidth()) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return cropped;
    }

    private void runDemoAnimation() {
        if (!overlayVisible) return;
        if (demoAnimator != null) demoAnimator.cancel();
        demoAnimator = ValueAnimator.ofFloat(openReferenceAngle, 68f);
        demoAnimator.setDuration(1700);
        demoAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        demoAnimator.addUpdateListener(a -> overlayView.setAngle((float) a.getAnimatedValue()));
        demoAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                main.postDelayed(DuoEffectService.this::hideOverlay, 450);
            }
        });
        demoAnimator.start();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (hingeSensor == null || event.sensor != hingeSensor || event.values.length == 0) return;
        float raw = event.values[0];
        if (firstRaw == null && Float.isFinite(raw)) firstRaw = raw;
        float deg = SensorUtil.normalizeHingeDegrees(hingeSensor, raw, firstRaw);
        if (!Float.isFinite(deg)) return;

        float previous = hasAngle ? smoothedAngle : deg;
        if (!hasAngle) smoothedAngle = deg;
        else smoothedAngle += (deg - smoothedAngle) * 0.62f;
        hasAngle = true;

        if (!overlayVisible && deg > 160f) {
            openReferenceAngle = Math.max(openReferenceAngle, deg);
            openReferenceAngle = Math.min(180f, openReferenceAngle);
            overlayView.setOpenReference(openReferenceAngle);
        }

        boolean closing = smoothedAngle < previous - 0.025f;
        float armThreshold = Math.max(MIN_ARM_ANGLE, openReferenceAngle - 4.0f);
        float triggerThreshold = Math.max(160f, openReferenceAngle - TRIGGER_DELTA);

        if (smoothedAngle >= armThreshold) {
            foldArmed = true;
            if (overlayVisible && !lastCoverAspect) hideOverlay();
        }

        if (foldArmed && closing && smoothedAngle < triggerThreshold && smoothedAngle > 28f) {
            foldArmed = false;
            Bitmap frame = detachLatestFrame();
            if (frame == null || frame.isRecycled()) {
                frame = makeDiagnosticBitmap("HINGE TRIGGERED\nNO CAPTURE FRAME");
            }
            showOverlay(frame, smoothedAngle);
        }

        if (overlayVisible && !overlayView.isCoverMode()) {
            overlayView.setAngle(smoothedAngle);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onDisplayAdded(int displayId) { main.postDelayed(this::resizeCaptureToCurrentScreen, 60); }
    @Override public void onDisplayRemoved(int displayId) { main.postDelayed(this::resizeCaptureToCurrentScreen, 60); }
    @Override public void onDisplayChanged(int displayId) { main.postDelayed(this::resizeCaptureToCurrentScreen, 60); }

    private void toast(String text) {
        main.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        if (demoAnimator != null) demoAnimator.cancel();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (displayManager != null) displayManager.unregisterDisplayListener(this);
        if (overlayAdded && windowManager != null) {
            try { windowManager.removeViewImmediate(overlayView); } catch (Throwable ignored) { }
        }
        overlayAdded = false;
        if (virtualDisplay != null) virtualDisplay.release();
        virtualDisplay = null;
        if (imageReader != null) imageReader.close();
        imageReader = null;
        if (projection != null) projection.stop();
        projection = null;
        if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
        latestFrame = null;
        if (frozenFrame != null && !frozenFrame.isRecycled()) frozenFrame.recycle();
        frozenFrame = null;
        if (captureThread != null) captureThread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
