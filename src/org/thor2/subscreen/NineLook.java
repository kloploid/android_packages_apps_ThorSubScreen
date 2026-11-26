package org.thor2.subscreen;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

/**
 * The font every Nine app uses: monospace or proportional, as chosen in the phone's first-time
 * setup (NineTailsFlip's Setup serves it from io.github.ninetailsflip.setup.choices, the same
 * provider nine-core's SetupChoices reads). Monospace when there's no choice. Read at most once
 * a minute: the outer screen redraws often.
 */
final class NineLook {
    private static final String TAG = "SubScreen";
    private static final Uri CHOICES = Uri.parse("content://io.github.ninetailsflip.setup.choices/look");
    private static final long TTL_MS = 60_000;

    private static boolean sMonospace = true;
    private static long sReadAt = -TTL_MS;

    private NineLook() {
    }

    static synchronized boolean monospace(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (now - sReadAt < TTL_MS) {
            return sMonospace;
        }
        sReadAt = now;
        try (Cursor c = context.getContentResolver().query(CHOICES, new String[] {"key", "value"}, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if ("use_monospace_font".equals(c.getString(0))) {
                        sMonospace = !"false".equals(c.getString(1));
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "setup choices: " + e.getMessage());
        }
        return sMonospace;
    }
}
