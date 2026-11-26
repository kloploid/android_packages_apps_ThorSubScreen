package org.thor2.subscreen;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;

/**
 * The outer screen's and the keys' settings, for the phone's Settings app (NineTailsFlip's
 * Settings: Display → Outer screen, System → Buttons and lid), which shows and changes them.
 *
 * - query: every stored setting as rows of key, value (as text) and type ("boolean", "int",
 *   "string"); a setting not stored yet has its default, which Settings knows.
 * - update: the ContentValues' keys are written, each with its value's type; a null value
 *   removes the key (back to the default).
 * - call "show_now": lights the outer screen up on the clock.
 *
 * Only holders of WRITE_SECURE_SETTINGS (signature|privileged) may read or write.
 */
public class PrefsProvider extends ContentProvider {
    static final String AUTHORITY = "org.thor2.subscreen.prefs";
    static final String METHOD_SHOW_NOW = "show_now";
    private static final String PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS;

    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences prefs() {
        return new Prefs(getContext()).raw();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {"key", "value", "type"});
        for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
            Object v = e.getValue();
            String type = v instanceof Boolean ? "boolean" : v instanceof Integer ? "int" : "string";
            cursor.addRow(new Object[] {e.getKey(), String.valueOf(v), type});
        }
        return cursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        SharedPreferences.Editor editor = prefs().edit();
        int n = 0;
        for (String key : values.keySet()) {
            Object v = values.get(key);
            if (v == null) {
                editor.remove(key);
            } else if (v instanceof Boolean) {
                editor.putBoolean(key, (Boolean) v);
            } else if (v instanceof Integer) {
                editor.putInt(key, (Integer) v);
            } else {
                editor.putString(key, v.toString());
            }
            n++;
        }
        editor.apply();
        return n;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingOrSelfPermission(PERMISSION, "outer screen settings");
        if (METHOD_SHOW_NOW.equals(method)) {
            SubScreenController.get(getContext()).showNow();
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("update instead");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("update with null values instead");
    }
}
