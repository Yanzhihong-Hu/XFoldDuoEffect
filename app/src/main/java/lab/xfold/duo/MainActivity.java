package lab.xfold.duo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_CAPTURE = 5001;
    private static final int REQ_NOTIF = 5002;

    private SensorManager sensorManager;
    private Sensor hingeSensor;
    private Float firstRaw;
    private TextView sensorStatus;
    private TextView angleStatus;
    private TextView serviceStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        hingeSensor = SensorUtil.findBestHingeSensor(sensorManager);
        setContentView(buildUi());
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
        TextView sub = text("无 Root 的系统级实验版：读取铰链角度 + 屏幕捕获 + 全屏 GPU 模糊/投影叠层", 13,
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
        LinearLayout.LayoutParams sLp = cardLp(); sLp.topMargin = 0; sLp.bottomMargin = dp(16);
        root.addView(serviceStatus, sLp);

        root.addView(cardTitle("启动"));
        Button overlay = primaryButton("① 授予“显示在其他应用上层”");
        overlay.setOnClickListener(v -> openOverlayPermission());
        root.addView(overlay, buttonLp());

        Button start = primaryButton("② 启动 Duo 折叠特效");
        start.setOnClickListener(v -> startDuoEffect());
        root.addView(start, buttonLp());

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
                "使用方式\n\n" +
                "• 第一次启动会出现 Android 的屏幕共享/录制授权。这里只在本机内存中取当前画面，用来做折叠过渡。\n" +
                "• 当铰链从接近 180° 开始合拢时，程序先冻结一帧，再让移动半屏保持“固定投影”，离铰链越远越模糊、越暗。\n" +
                "• OriginOS 切到外屏后，冻结的内屏画面会裁到外屏一侧并快速淡出，让真实外屏内容接管。\n" +
                "• 特效叠层不接收触摸；动画结束后自动隐藏。",
                13, Color.rgb(177, 185, 210), false);
        how.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams howLp = cardLp(); howLp.topMargin = dp(6); howLp.bottomMargin = dp(16);
        root.addView(how, howLp);

        Button report = secondaryButton("分享传感器报告");
        report.setOnClickListener(v -> shareSensorReport());
        root.addView(report, buttonLp());

        TextView note = text("v0.2 是针对你的 V2337A / X Fold3 Pro 做的关闭折叠方向原型。若 type 36 在 OriginOS 上能连续出角度，真机合拢就会直接驱动动画。",
                12, Color.rgb(126, 137, 168), false);
        LinearLayout.LayoutParams nLp = lp(); nLp.topMargin = dp(12);
        root.addView(note, nLp);

        return scroll;
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
    public void onSensorChanged(SensorEvent event) {
        if (hingeSensor == null || event.sensor != hingeSensor || event.values.length == 0) return;
        float raw = event.values[0];
        if (firstRaw == null && Float.isFinite(raw)) firstRaw = raw;
        float deg = SensorUtil.normalizeHingeDegrees(hingeSensor, raw, firstRaw);
        if (Float.isFinite(deg)) {
            angleStatus.setText(String.format(Locale.US, "%.1f°", deg));
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
    }

    private void shareSensorReport() {
        String report = SensorUtil.fullReport(sensorManager)
                + "\nBuild.MANUFACTURER=" + android.os.Build.MANUFACTURER
                + "\nBuild.MODEL=" + android.os.Build.MODEL
                + "\nBuild.DEVICE=" + android.os.Build.DEVICE
                + "\nSDK=" + android.os.Build.VERSION.SDK_INT
                + "\nRelease=" + android.os.Build.VERSION.RELEASE + "\n";
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "X Fold Duo sensor report");
        i.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(i, "分享传感器报告"));
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
}
