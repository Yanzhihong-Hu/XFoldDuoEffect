package lab.xfold.duo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.window.area.WindowAreaCapability;
import androidx.window.area.WindowAreaController;
import androidx.window.area.WindowAreaInfo;
import androidx.window.area.WindowAreaPresentationSessionCallback;
import androidx.window.area.WindowAreaSessionPresenter;
import androidx.window.core.ExperimentalWindowApi;
import androidx.window.java.area.WindowAreaControllerCallbackAdapter;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

@OptIn(markerClass = ExperimentalWindowApi.class)
public class MainActivity extends Activity implements SensorEventListener, WindowAreaPresentationSessionCallback {
    private static final int REQ_CAPTURE = 5001;
    private static final int REQ_NOTIF = 5002;

    private SensorManager sensorManager;
    private Sensor hingeSensor;
    private Float firstRaw;
    private TextView sensorStatus;
    private TextView angleStatus;
    private TextView serviceStatus;
    private TextView dualScreenStatus;
    private Button dualScreenButton;

    private WindowAreaControllerCallbackAdapter windowAreaController;
    private WindowAreaInfo rearAreaInfo;
    private WindowAreaCapability.Status rearPresentStatus =
            WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED;
    private WindowAreaSessionPresenter rearSession;
    private Executor displayExecutor;
    private Consumer<List<WindowAreaInfo>> areaListener;
    private OuterPreviewView outerPreviewView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        hingeSensor = SensorUtil.findBestHingeSensor(sensorManager);
        setContentView(buildUi());
        setupDualScreenSupport();
        maybeRequestNotificationPermission();
        refreshStatus();
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(8, 10, 15));
        w.setNavigationBarColor(Color.rgb(8, 10, 15));
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    }

    private View buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 10, 15));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(18), pad, dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("X Fold3 Pro · Duo Effect", 25, Color.rgb(245, 247, 255), true);
        root.addView(title);
        TextView sub = text("v0.5：内屏 Duo 动画 + Android 官方双屏/外屏能力探测", 13,
                Color.rgb(151, 160, 190), false);
        LinearLayout.LayoutParams subLp = lp(); subLp.topMargin = dp(6); subLp.bottomMargin = dp(18);
        root.addView(sub, subLp);

        root.addView(cardTitle("设备状态"));
        sensorStatus = text("铰链：检查中", 14, Color.WHITE, true);
        sensorStatus.setPadding(dp(14), dp(12), dp(14), dp(2));
        root.addView(sensorStatus, cardLp());
        angleStatus = text("角度：--", 30, Color.rgb(211, 218, 255), true);
        angleStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams aLp = cardLp(); aLp.topMargin = 0; aLp.bottomMargin = 0;
        root.addView(angleStatus, aLp);
        serviceStatus = text("特效服务：未启动", 13, Color.rgb(158, 169, 201), false);
        serviceStatus.setPadding(dp(14), dp(4), dp(14), dp(13));
        LinearLayout.LayoutParams sLp = cardLp(); sLp.topMargin = 0; sLp.bottomMargin = dp(12);
        root.addView(serviceStatus, sLp);

        dualScreenStatus = text("外屏双屏能力：检测中…", 13, Color.rgb(255, 207, 133), true);
        dualScreenStatus.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams dslp = cardLp(); dslp.bottomMargin = dp(14);
        root.addView(dualScreenStatus, dslp);

        root.addView(cardTitle("启动"));
        Button overlay = primaryButton("① 授予“显示在其他应用上层”");
        overlay.setOnClickListener(v -> openOverlayPermission());
        root.addView(overlay, buttonLp());

        Button start = primaryButton("② 启动 Duo 折叠特效");
        start.setOnClickListener(v -> startDuoEffect());
        root.addView(start, buttonLp());

        dualScreenButton = primaryButton("③ 尝试点亮外屏 / 双屏模式");
        dualScreenButton.setOnClickListener(v -> toggleDualScreen());
        dualScreenButton.setEnabled(false);
        root.addView(dualScreenButton, buttonLp());

        Button demo = secondaryButton("在当前内屏测试一次动画");
        demo.setOnClickListener(v -> sendServiceAction(DuoEffectService.ACTION_DEMO_CLOSE));
        root.addView(demo, buttonLp());

        Button stop = secondaryButton("停止特效服务");
        stop.setOnClickListener(v -> {
            sendServiceAction(DuoEffectService.ACTION_STOP);
            serviceStatus.setText("特效服务：正在停止");
        });
        root.addView(stop, buttonLp());

        TextView how = text(
                "这版专门验证你刚发现的问题：内屏特效已经能跑，但 OriginOS 默认不会同时点亮外屏。\n\n" +
                "• 第 ③ 个按钮调用 Android/Jetpack WindowManager 的官方 dual-screen API。\n" +
                "• 如果 vivo 在 X Fold3 Pro 上实现了这个能力，外屏会在手机仍展开时被系统正式点亮，并显示 DUO OUTER ACTIVE。\n" +
                "• 如果状态显示 UNSUPPORTED 或 UNAVAILABLE，说明 OriginOS 没把外屏作为第三方应用可控制的 WindowArea 暴露出来；普通无 Root APK 就不能提前强制点亮它。\n" +
                "• 若双屏能力可用，下一版会把真实内屏截图和模糊/清晰转换直接送到外屏。",
                13, Color.rgb(177, 185, 210), false);
        how.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams howLp = cardLp(); howLp.topMargin = dp(6); howLp.bottomMargin = dp(16);
        root.addView(how, howLp);

        Button report = secondaryButton("分享完整诊断报告（传感器 + 屏幕）");
        report.setOnClickListener(v -> shareSensorReport());
        root.addView(report, buttonLp());

        TextView note = text("目标仍然是：内屏画面固定投影并渐进失焦，同时外屏提前亮起并从模糊过渡到清晰。v0.5 先确认 vivo 是否开放了官方双屏通道。",
                12, Color.rgb(126, 137, 168), false);
        LinearLayout.LayoutParams nLp = lp(); nLp.topMargin = dp(12);
        root.addView(note, nLp);

        return scroll;
    }

    private void setupDualScreenSupport() {
        try {
            displayExecutor = ContextCompat.getMainExecutor(this);
            windowAreaController = new WindowAreaControllerCallbackAdapter(WindowAreaController.getOrCreate());
            areaListener = infos -> {
                WindowAreaInfo found = null;
                WindowAreaCapability.Status foundStatus =
                        WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED;
                for (WindowAreaInfo info : infos) {
                    if (WindowAreaInfo.Type.TYPE_REAR_FACING.equals(info.getType())) {
                        found = info;
                        try {
                            foundStatus = info.getCapability(
                                    WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA).getStatus();
                        } catch (Throwable ignored) { }
                        break;
                    }
                }
                rearAreaInfo = found;
                rearPresentStatus = foundStatus;
                updateDualScreenUi();
            };
            windowAreaController.addWindowAreaInfoListListener(displayExecutor, areaListener);
        } catch (Throwable t) {
            rearPresentStatus = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED;
            if (dualScreenStatus != null) {
                dualScreenStatus.setText("外屏双屏能力：API 初始化失败 · " + t.getClass().getSimpleName());
                dualScreenStatus.setTextColor(Color.rgb(255, 166, 143));
            }
        }
    }

    private void updateDualScreenUi() {
        if (dualScreenStatus == null || dualScreenButton == null) return;
        String s;
        boolean enable = false;
        if (rearSession != null) {
            s = "ACTIVE · 外屏双屏会话已启动";
            enable = true;
            dualScreenButton.setText("③ 关闭外屏双屏模式");
            dualScreenStatus.setTextColor(Color.rgb(177, 241, 197));
        } else if (rearPresentStatus.equals(WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE)) {
            s = "AVAILABLE · vivo 已开放双屏外屏 API";
            enable = true;
            dualScreenButton.setText("③ 点亮外屏 / 启动双屏模式");
            dualScreenStatus.setTextColor(Color.rgb(177, 241, 197));
        } else if (rearPresentStatus.equals(WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE)) {
            s = "ACTIVE · 系统报告双屏已被占用/激活";
            enable = false;
            dualScreenButton.setText("③ 外屏双屏模式");
            dualScreenStatus.setTextColor(Color.rgb(177, 241, 197));
        } else if (rearPresentStatus.equals(WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNAVAILABLE)) {
            s = "UNAVAILABLE · 当前姿态/系统状态不允许第三方点亮外屏";
            dualScreenButton.setText("③ 外屏双屏模式当前不可用");
            dualScreenStatus.setTextColor(Color.rgb(255, 207, 133));
        } else {
            s = "UNSUPPORTED · OriginOS 未向第三方开放双屏外屏能力";
            dualScreenButton.setText("③ 外屏双屏模式不受支持");
            dualScreenStatus.setTextColor(Color.rgb(255, 166, 143));
        }
        dualScreenStatus.setText("外屏双屏能力：" + s);
        dualScreenButton.setEnabled(enable);
    }

    private void toggleDualScreen() {
        if (rearSession != null) {
            try { rearSession.close(); } catch (Throwable ignored) { }
            rearSession = null;
            updateDualScreenUi();
            return;
        }
        if (windowAreaController == null || rearAreaInfo == null) {
            toast("系统没有暴露可用的 rear-facing WindowArea。请分享完整诊断报告。");
            return;
        }
        if (!rearPresentStatus.equals(WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE)) {
            toast("当前外屏双屏状态不是 AVAILABLE：" + rearPresentStatus);
            return;
        }
        try {
            windowAreaController.presentContentOnWindowArea(
                    rearAreaInfo.getToken(), this, displayExecutor, this);
            toast("正在请求 OriginOS 同时点亮外屏…");
        } catch (Throwable t) {
            toast("双屏请求失败：" + t.getClass().getSimpleName() + " · " + safeMessage(t));
        }
    }

    @Override
    public void onSessionStarted(@NonNull WindowAreaSessionPresenter session) {
        rearSession = session;
        outerPreviewView = new OuterPreviewView(session.getContext());
        session.setContentView(outerPreviewView);
        updateDualScreenUi();
        toast("外屏双屏会话已启动。现在看手机外屏。 ");
    }

    @Override
    public void onSessionEnded(@Nullable Throwable t) {
        rearSession = null;
        outerPreviewView = null;
        updateDualScreenUi();
        if (t != null) toast("外屏双屏会话结束：" + safeMessage(t));
    }

    @Override
    public void onContainerVisibilityChanged(boolean isVisible) {
        if (dualScreenStatus != null && rearSession != null) {
            dualScreenStatus.setText("外屏双屏能力：ACTIVE · 外屏内容可见=" + isVisible);
        }
    }

    private void startDuoEffect() {
        if (!Settings.canDrawOverlays(this)) {
            toast("先打开“显示在其他应用上层”。返回后再点启动。 ");
            openOverlayPermission();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            toast("系统没有 MediaProjection 服务。");
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()), REQ_CAPTURE);
    }

    private void openOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                toast("没有获得屏幕共享权限，特效未启动。");
                return;
            }
            Intent s = new Intent(this, DuoEffectService.class);
            s.setAction(DuoEffectService.ACTION_START);
            s.putExtra(DuoEffectService.EXTRA_RESULT_CODE, resultCode);
            s.putExtra(DuoEffectService.EXTRA_RESULT_DATA, data);
            startForegroundService(s);
            serviceStatus.setText("特效服务：启动中…");
            toast("已启动。保持服务运行，然后实际合拢手机测试。 ");
        }
    }

    private void sendServiceAction(String action) {
        Intent i = new Intent(this, DuoEffectService.class);
        i.setAction(action);
        try {
            startService(i);
        } catch (Exception e) {
            toast("服务还没启动。先完成上面的第 ② 步。 ");
        }
    }

    private void maybeRequestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hingeSensor != null && sensorManager != null) {
            sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (windowAreaController != null && areaListener != null) {
            try { windowAreaController.removeWindowAreaInfoListListener(areaListener); } catch (Throwable ignored) { }
        }
        if (rearSession != null) {
            try { rearSession.close(); } catch (Throwable ignored) { }
        }
        rearSession = null;
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (hingeSensor == null || event.sensor != hingeSensor || event.values.length == 0) return;
        float raw = event.values[0];
        if (firstRaw == null && Float.isFinite(raw)) firstRaw = raw;
        float deg = SensorUtil.normalizeHingeDegrees(hingeSensor, raw, firstRaw);
        if (Float.isFinite(deg)) {
            angleStatus.setText(String.format(Locale.US, "%.1f°", deg));
            if (outerPreviewView != null) outerPreviewView.setAngle(deg);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void refreshStatus() {
        if (sensorStatus == null) return;
        if (hingeSensor != null) {
            sensorStatus.setText("铰链：" + SensorUtil.describe(hingeSensor));
            sensorStatus.setTextColor(Color.rgb(177, 241, 197));
        } else {
            sensorStatus.setText("铰链：标准 type 36 未发现；可发传感器报告继续适配 vivo 私有流");
            sensorStatus.setTextColor(Color.rgb(255, 190, 154));
        }
        if (Settings.canDrawOverlays(this)) {
            serviceStatus.setText("悬浮权限：已允许 · 屏幕捕获需启动时授权");
        } else {
            serviceStatus.setText("悬浮权限：未允许");
        }
        updateDualScreenUi();
    }

    private void shareSensorReport() {
        StringBuilder report = new StringBuilder(SensorUtil.fullReport(sensorManager));
        report.append("\nBuild.MANUFACTURER=").append(android.os.Build.MANUFACTURER)
                .append("\nBuild.MODEL=").append(android.os.Build.MODEL)
                .append("\nBuild.DEVICE=").append(android.os.Build.DEVICE)
                .append("\nSDK=").append(android.os.Build.VERSION.SDK_INT)
                .append("\nRelease=").append(android.os.Build.VERSION.RELEASE)
                .append("\nWindowArea rear present status=").append(rearPresentStatus)
                .append("\nWindowArea rear area present=").append(rearAreaInfo != null)
                .append("\nWindowArea session active=").append(rearSession != null)
                .append("\n\nDISPLAY REPORT\n");

        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            for (Display d : dm.getDisplays()) {
                Display.Mode mode = d.getMode();
                report.append("id=").append(d.getDisplayId())
                        .append(" name=").append(d.getName())
                        .append(" state=").append(displayStateName(d.getState()))
                        .append(" flags=0x").append(Integer.toHexString(d.getFlags()))
                        .append(" size=").append(mode.getPhysicalWidth()).append("x").append(mode.getPhysicalHeight())
                        .append(" refresh=").append(mode.getRefreshRate())
                        .append("\n");
            }
        }

        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "X Fold Duo full diagnostic report");
        i.putExtra(Intent.EXTRA_TEXT, report.toString());
        startActivity(Intent.createChooser(i, "分享完整诊断报告"));
    }

    private static String displayStateName(int state) {
        switch (state) {
            case Display.STATE_OFF: return "OFF";
            case Display.STATE_ON: return "ON";
            case Display.STATE_DOZE: return "DOZE";
            case Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case Display.STATE_VR: return "VR";
            case Display.STATE_ON_SUSPEND: return "ON_SUSPEND";
            default: return String.valueOf(state);
        }
    }

    private TextView cardTitle(String s) {
        TextView t = text(s, 13, Color.rgb(122, 134, 170), true);
        LinearLayout.LayoutParams p = lp(); p.bottomMargin = dp(7);
        t.setLayoutParams(p);
        return t;
    }

    private Button primaryButton(String s) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(15); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.rgb(74, 84, 220)); gd.setCornerRadius(dp(18));
        b.setBackground(gd);
        return b;
    }

    private Button secondaryButton(String s) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(14); b.setTextColor(Color.rgb(230, 233, 248)); b.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.rgb(27, 31, 44)); gd.setCornerRadius(dp(18));
        gd.setStroke(dp(1), Color.rgb(55, 62, 88));
        b.setBackground(gd);
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        t.setLineSpacing(0, 1.14f);
        return t;
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams p = lp();
        p.topMargin = dp(4); p.bottomMargin = dp(4);
        return p;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        p.bottomMargin = dp(10);
        return p;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private static String safeMessage(Throwable t) {
        String s = t == null ? null : t.getMessage();
        return s == null || s.isEmpty() ? "无详细信息" : s;
    }

    private static final class OuterPreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float angle = 175f;

        OuterPreviewView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            setKeepScreenOn(true);
        }

        void setAngle(float value) {
            angle = value;
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float p = Math.max(0f, Math.min(1f, (175f - angle) / 95f));
            paint.setColor(Color.rgb((int)(24 + 55 * p), (int)(35 + 60 * p), (int)(70 + 150 * p)));
            canvas.drawRect(0, 0, w, h, paint);

            paint.setColor(Color.argb((int)(70 + 150 * p), 130, 170, 255));
            float radius = Math.min(w, h) * (0.18f + 0.18f * p);
            canvas.drawCircle(w * 0.5f, h * 0.52f, radius, paint);

            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(Math.max(34f, w * 0.055f));
            canvas.drawText("DUO OUTER ACTIVE", w / 2f, h * 0.42f, paint);
            paint.setTextSize(Math.max(28f, w * 0.045f));
            canvas.drawText(String.format(Locale.US, "%.1f°", angle), w / 2f, h * 0.60f, paint);
            paint.setTextSize(Math.max(20f, w * 0.030f));
            canvas.drawText("Android official dual-screen session", w / 2f, h * 0.70f, paint);
        }
    }
}
