package org.thor2.subscreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.Calendar;

/**
 * Настройки внешнего экрана. Хранятся в device-protected storage (доступны до первой
 * разблокировки); экран настроек пишет в тот же файл (см. SettingsFragment).
 */
final class Prefs {
    static final String FILE = "subscreen";

    static final String ENABLED = "enabled";
    static final String TIMEOUT = "timeout";
    static final String BRIGHTNESS = "brightness";
    static final String NIGHT = "night_mode";
    static final String NIGHT_START = "night_start";
    static final String NIGHT_END = "night_end";

    static final String WAKE_LID = "wake_lid";
    static final String WAKE_SOS = "wake_sos";
    static final String WAKE_VOLUME = "wake_volume";
    static final String WAKE_NOTIFICATIONS = "wake_notifications";
    static final String WAKE_CHARGING = "wake_charging";

    static final String CLOCK_STYLE = "clock_style";
    static final String CLOCK_FORMAT = "clock_format";
    static final String SECONDS = "show_seconds";
    static final String DATE = "show_date";
    static final String ACCENT = "accent_color";
    static final String BACKGROUND = "background";
    static final String SIGNATURE = "signature";

    static final String BATTERY_STYLE = "battery_style";
    static final String NOTIF_ICONS = "notif_icons";
    static final String NOTIF_TEXT = "notif_text";
    static final String MISSED = "show_missed";

    static final String PAGE_MUSIC = "page_music";
    static final String PAGE_NOTIFICATIONS = "page_notifications";
    static final String PAGE_PET = "page_pet";
    static final String PAGE_EIGHTBALL = "page_eightball";
    static final String STEPS = "show_steps";
    static final String CHARGING_ANIM = "charging_anim";
    static final String TORCH_DOUBLE_SOS = "torch_double_sos";

    /** Кнопки и крышка (экран «Кнопки и крышка»). */
    static final String LID_ANSWER = "lid_answer";
    static final String LID_END = "lid_end";
    /** Зеркало Settings.Global thor2_home_long_press_shade (его читает PhoneWindowManager). */
    static final String HOME_SHADE = "home_long_press_shade";

    /** Служебные (не на экране настроек). */
    static final String HALL_CLOSED = "hall_closed_value";
    static final String STEPS_DAY = "steps_day";
    static final String STEPS_BASE = "steps_base";

    /** Фото для фона (подготовленное 284×240) в device-protected files. */
    static final String PHOTO_FILE = "background.png";

    private final SharedPreferences mPrefs;

    Prefs(Context context) {
        mPrefs = storage(context).getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static Context storage(Context context) {
        return context.createDeviceProtectedStorageContext();
    }

    SharedPreferences raw() {
        return mPrefs;
    }

    boolean enabled() {
        return mPrefs.getBoolean(ENABLED, true);
    }

    long timeoutMs() {
        return parse(TIMEOUT, 8) * 1000L;
    }

    /** Яркость в процентах (1..100). */
    int brightnessPercent() {
        return isNight() ? Math.min(25, mPrefs.getInt(BRIGHTNESS, 60)) : mPrefs.getInt(BRIGHTNESS, 60);
    }

    boolean isNight() {
        if (!mPrefs.getBoolean(NIGHT, false)) {
            return false;
        }
        int start = parse(NIGHT_START, 23);
        int end = parse(NIGHT_END, 7);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return start <= end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    boolean wake(String key) {
        boolean def = !WAKE_VOLUME.equals(key);
        if (WAKE_NOTIFICATIONS.equals(key) && isNight()) {
            return false;  // ночью уведомления экран не будят
        }
        return mPrefs.getBoolean(key, def);
    }

    String clockStyle() {
        return mPrefs.getString(CLOCK_STYLE, "digital");
    }

    /** null — как в системе, иначе true = 24 ч. */
    Boolean force24h() {
        String f = mPrefs.getString(CLOCK_FORMAT, "system");
        return "24".equals(f) ? Boolean.TRUE : "12".equals(f) ? Boolean.FALSE : null;
    }

    boolean seconds() {
        return mPrefs.getBoolean(SECONDS, false);
    }

    boolean date() {
        return mPrefs.getBoolean(DATE, true);
    }

    int accent() {
        switch (mPrefs.getString(ACCENT, "white")) {
            case "green": return Color.rgb(80, 220, 120);
            case "cyan": return Color.rgb(70, 200, 240);
            case "amber": return Color.rgb(255, 190, 60);
            case "pink": return Color.rgb(255, 110, 170);
            case "purple": return Color.rgb(170, 130, 255);
            case "red": return Color.rgb(255, 90, 80);
            default: return Color.WHITE;
        }
    }

    String background() {
        return mPrefs.getString(BACKGROUND, "black");
    }

    String signature() {
        return mPrefs.getString(SIGNATURE, "").trim();
    }

    String batteryStyle() {
        return mPrefs.getString(BATTERY_STYLE, "icon_percent");
    }

    boolean on(String key) {
        // текст уведомлений на внешнем экране и ответ/сброс вызова крышкой — только по желанию
        boolean def = !NOTIF_TEXT.equals(key) && !LID_ANSWER.equals(key) && !LID_END.equals(key);
        return mPrefs.getBoolean(key, def);
    }

    private int parse(String key, int def) {
        try {
            return Integer.parseInt(mPrefs.getString(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
