package lab.xfold.duo;

import android.content.Context;
import android.content.SharedPreferences;

final class DuoPrefs {
    private static final String NAME = "duo";
    private static final String MOVING_RIGHT = "movingRight";
    private static final String OPENING = "openingEffect";

    private DuoPrefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** The X Fold3 Pro cover screen sits behind the left half, so by default the left half moves. */
    static boolean movingRight(Context c) { return prefs(c).getBoolean(MOVING_RIGHT, false); }

    static void setMovingRight(Context c, boolean v) { prefs(c).edit().putBoolean(MOVING_RIGHT, v).apply(); }

    static boolean openingEnabled(Context c) { return prefs(c).getBoolean(OPENING, true); }

    static void setOpeningEnabled(Context c, boolean v) { prefs(c).edit().putBoolean(OPENING, v).apply(); }
}
