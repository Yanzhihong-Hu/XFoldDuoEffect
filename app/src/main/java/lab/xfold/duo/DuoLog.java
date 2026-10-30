package lab.xfold.duo;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** In-memory event log so a fold that misbehaves on the phone can be shared in the report. */
final class DuoLog {
    private static final int MAX_LINES = 80;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DuoLog() {}

    static synchronized void add(String message) {
        android.util.Log.i("XFoldDuo", message);
        if (lines.size() >= MAX_LINES) lines.removeFirst();
        lines.addLast(time.format(new Date()) + "  " + message);
    }

    static synchronized String dump() {
        if (lines.isEmpty()) return "(no events yet)\n";
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.toString();
    }
}
