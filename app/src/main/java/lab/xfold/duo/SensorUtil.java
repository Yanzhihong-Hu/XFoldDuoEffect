package lab.xfold.duo;

import android.hardware.Sensor;
import android.hardware.SensorManager;

import java.util.List;
import java.util.Locale;

final class SensorUtil {
    private SensorUtil() {}

    static Sensor findBestHingeSensor(SensorManager sm) {
        if (sm == null) return null;
        Sensor standard = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (standard != null) return standard;

        Sensor best = null;
        int bestScore = 0;
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : all) {
            String hay = (safe(s.getName()) + " " + safe(s.getVendor()) + " " + safe(s.getStringType()))
                    .toLowerCase(Locale.US);
            int score = 0;
            if (hay.contains("hinge")) score += 120;
            if (hay.contains("foldstatus")) score += 110;
            if (hay.contains("fold")) score += 80;
            if (hay.contains("posture")) score += 45;
            if (hay.contains("angle")) score += 25;
            if (hay.contains("vivo")) score += 5;
            if (hay.contains("gyro") || hay.contains("rotation vector") || hay.contains("accelerometer")) score -= 100;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return bestScore >= 45 ? best : null;
    }

    static float normalizeHingeDegrees(Sensor sensor, float raw, Float firstRaw) {
        if (!Float.isFinite(raw)) return Float.NaN;
        float degrees = raw;
        if (sensor != null && sensor.getType() != Sensor.TYPE_HINGE_ANGLE) {
            // Many vendor sensors still report degrees. If the first value while the app starts
            // on an unfolded device looks like pi, treat that vendor stream as radians.
            if (firstRaw != null && firstRaw > 2.2f && firstRaw < 3.5f && Math.abs(raw) <= 6.5f) {
                degrees = (float) Math.toDegrees(raw);
            }
        }
        if (degrees > 180f && degrees <= 360f) degrees = 360f - degrees;
        return clamp(degrees, 0f, 180f);
    }

    static String describe(Sensor sensor) {
        if (sensor == null) return "未发现可用铰链传感器";
        return sensor.getName() + " · type " + sensor.getType() + " · " + sensor.getVendor();
    }

    static String fullReport(SensorManager sm) {
        StringBuilder b = new StringBuilder();
        b.append("XFoldDuoLab sensor report\n");
        if (sm == null) return b.append("SensorManager unavailable\n").toString();
        for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            b.append("type=").append(s.getType())
                    .append(" name=").append(s.getName())
                    .append(" stringType=").append(s.getStringType())
                    .append(" vendor=").append(s.getVendor())
                    .append(" wakeUp=").append(s.isWakeUpSensor())
                    .append(" maxRange=").append(s.getMaximumRange())
                    .append(" resolution=").append(s.getResolution())
                    .append('\n');
        }
        return b.toString();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
