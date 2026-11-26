package org.thor2.subscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Рисует кадр внешнего экрана (альбомный {@link Panel#WIDTH}×{@link Panel#HEIGHT}) в виде Nine,
 * как приложения на главном экране: чёрный фон, только текст (моноширинный, как выбрано в
 * первой настройке телефона), белое — главное, серое — подробности, мелкие серые заглавные —
 * заголовок страницы, внизу строка подсказки под тонкой линией. Без значков, эмодзи и цвета;
 * зелёное — только «идёт»: входящий звонок, зарядка.
 */
final class Renderer {
    enum Page { CLOCK, CALL, MUSIC, NOTIFICATIONS, PET, EIGHTBALL, CHARGING }

    /** Уведомление для экрана. */
    static final class Notice {
        String app;
        String title;
        String text;
        Drawable icon;
    }

    /** Что показывать; заполняет {@link SubScreenController}. */
    static final class State {
        Page page = Page.CLOCK;
        int batteryLevel = -1;
        boolean charging;
        boolean ringing;
        boolean inCall;
        String caller;
        String callerNumber;
        int missedCalls;
        List<Notice> notices = new ArrayList<>();
        String musicTitle;
        String musicArtist;
        Bitmap musicArt;
        boolean musicPlaying;
        int steps = -1;
        String eightBall;
        /** Короткая строка поверх (например, «Фонарик включён»): встаёт в строку подсказки. */
        String toast;
        int frame;

        State copy() {
            State c = new State();
            c.page = page;
            c.batteryLevel = batteryLevel;
            c.charging = charging;
            c.ringing = ringing;
            c.inCall = inCall;
            c.caller = caller;
            c.callerNumber = callerNumber;
            c.missedCalls = missedCalls;
            c.notices = new ArrayList<>(notices);
            c.musicTitle = musicTitle;
            c.musicArtist = musicArtist;
            c.musicArt = musicArt;
            c.musicPlaying = musicPlaying;
            c.steps = steps;
            c.eightBall = eightBall;
            c.frame = frame;
            return c;
        }
    }

    static final int W = Panel.WIDTH;
    static final int H = Panel.HEIGHT;

    // Палитра nine-core (life.furrer.nine.core.ui.Palette).
    static final int WHITE = Color.WHITE;
    static final int GRAY_DIM = 0xFF8A8A8A;
    static final int GRAY_MUTED = 0xFF666666;
    static final int GRAY_FAINT = 0xFF4D4D4D;
    static final int GREEN = 0xFF89E1A7;

    private static final int SIDE = 12;
    private static final int STATUS_BASELINE = 18;
    private static final int HINT_LINE = H - 22;
    private static final int HINT_BASELINE = H - 7;
    private static final int CONTENT_TOP = 30;

    private final Context mContext;
    private final Prefs mPrefs;
    private final Bitmap mBitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas mCanvas = new Canvas(mBitmap);
    private final Paint mPaint = new Paint();
    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Typeface mFace = Typeface.MONOSPACE;
    private Typeface mBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    Renderer(Context context, Prefs prefs) {
        mContext = context;
        mPrefs = prefs;
    }

    Bitmap draw(State s) {
        boolean mono = NineLook.monospace(mContext);
        mFace = mono ? Typeface.MONOSPACE : Typeface.DEFAULT;
        mBold = Typeface.create(mFace, Typeface.BOLD);
        mCanvas.drawColor(Color.BLACK);
        String hint;
        switch (s.page) {
            case CALL: hint = drawCall(s); break;
            case MUSIC: hint = drawMusic(s); break;
            case NOTIFICATIONS: hint = drawNotifications(s); break;
            case PET: hint = drawPet(s); break;
            case EIGHTBALL: hint = drawEightBall(s); break;
            case CHARGING: hint = drawCharging(s); break;
            default: hint = drawClock(s); break;
        }
        if (s.page != Page.CHARGING) {
            drawStatusLine(s);
        }
        boolean toast = !TextUtils.isEmpty(s.toast);
        drawHintBar(toast ? s.toast : hint, toast);
        return mBitmap;
    }

    // ---------------------------------------------------------------- рамка страницы

    /** Сверху: слева пропущенные, новые уведомления и шаги, справа заряд. */
    private void drawStatusLine(State s) {
        List<String> left = new ArrayList<>();
        if (mPrefs.on(Prefs.MISSED) && s.missedCalls > 0) {
            left.add(mContext.getResources().getQuantityString(R.plurals.status_missed, s.missedCalls, s.missedCalls));
        }
        if (mPrefs.on(Prefs.NOTIF_ICONS) && !s.notices.isEmpty() && s.page != Page.NOTIFICATIONS) {
            left.add(mContext.getResources().getQuantityString(R.plurals.status_new, s.notices.size(), s.notices.size()));
        }
        if (mPrefs.on(Prefs.STEPS) && s.steps >= 0 && s.page == Page.CLOCK) {
            left.add(mContext.getResources().getQuantityString(R.plurals.status_steps, s.steps, s.steps));
        }
        float right = W - SIDE;
        if (!"hidden".equals(mPrefs.batteryStyle()) && s.batteryLevel >= 0) {
            String battery = s.batteryLevel + " %";
            int color = s.charging ? GREEN : s.batteryLevel <= 15 ? WHITE : GRAY_DIM;
            right = text(battery, W - SIDE, STATUS_BASELINE, 13, false, color, Paint.Align.RIGHT) - 10;
        }
        if (!left.isEmpty()) {
            text(fit(TextUtils.join(" · ", left), 13, false, right - SIDE), SIDE, STATUS_BASELINE, 13, false, GRAY_DIM,
                    Paint.Align.LEFT);
        }
    }

    /** Внизу, как HintBar: тонкая линия и строка, что делать; сообщение — белым вместо неё. */
    private void drawHintBar(String text, boolean message) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        mPaint.setColor(GRAY_FAINT);
        mPaint.setStyle(Paint.Style.FILL);
        mCanvas.drawRect(0, HINT_LINE, W, HINT_LINE + 1, mPaint);
        text(fit(text, 12, false, W - 2 * SIDE), SIDE, HINT_BASELINE, 12, false, message ? WHITE : GRAY_MUTED,
                Paint.Align.LEFT);
    }

    /** Заголовок страницы: мелкие серые заглавные с разрядкой, как заголовок секции в списке. */
    private void header(String title, float baseline, int color) {
        mText.setLetterSpacing(0.1f);
        text(fit(title.toUpperCase(Locale.getDefault()), 12, false, W - 2 * SIDE), SIDE, baseline, 12, false, color,
                Paint.Align.LEFT);
        mText.setLetterSpacing(0f);
    }

    private String sosHint() {
        return mContext.getString(R.string.hint_next_page);
    }

    // ---------------------------------------------------------------- часы

    private boolean is24() {
        Boolean f = mPrefs.force24h();
        return f != null ? f : DateFormat.is24HourFormat(mContext);
    }

    private String hhmm(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (!is24()) {
            h = h % 12 == 0 ? 12 : h % 12;
            return h + ":" + two(c.get(Calendar.MINUTE));
        }
        return two(h) + ":" + two(c.get(Calendar.MINUTE));
    }

    private String dateLine() {
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        return cap(DateFormat.format(pattern, new Date()).toString());
    }

    private String drawClock(State s) {
        Calendar c = Calendar.getInstance();
        float below;
        if ("words".equals(mPrefs.clockStyle())) {
            below = drawWordClock(c);
        } else {
            below = drawDigitalClock(c);
        }
        if (mPrefs.date()) {
            text(fit(dateLine(), 18, false, W - 2 * SIDE), SIDE, below + 26, 18, false, GRAY_DIM, Paint.Align.LEFT);
            below += 26;
        }
        String sig = mPrefs.signature();
        if (!sig.isEmpty()) {
            text(fit(sig, 15, false, W - 2 * SIDE), SIDE, below + 24, 15, false, GRAY_MUTED, Paint.Align.LEFT);
        }
        return sosHint();
    }

    /** Время крупно слева, как на экране блокировки; секунды и AM/PM — мельче, серым. */
    private float drawDigitalClock(Calendar c) {
        String time = hhmm(c);
        float baseline = 104;
        float size = time.length() > 4 ? 72 : 80;
        float end = text(time, SIDE - 2, baseline, size, false, WHITE, Paint.Align.LEFT);
        String tail = mPrefs.seconds() ? ":" + two(c.get(Calendar.SECOND))
                : is24() ? null : (c.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM");
        if (tail != null) {
            text(tail, end + 4, baseline, 26, false, GRAY_DIM, Paint.Align.LEFT);
        }
        return baseline;
    }

    /** Время словами: фраза серым, слово-час белым. */
    private float drawWordClock(Calendar c) {
        boolean ru = "ru".equals(Locale.getDefault().getLanguage());
        String[] p = WordClock.phrase(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), ru);
        mText.setTypeface(mFace);
        mText.setColor(GRAY_DIM);
        float size = 30;
        StaticLayout layout;
        do {
            mText.setTextSize(size);
            layout = StaticLayout.Builder.obtain(p[0], 0, p[0].length(), mText, W - 2 * SIDE).build();
            size -= 2;
        } while (layout.getHeight() > 120 && size > 16);
        float top = CONTENT_TOP + 8;
        mCanvas.save();
        mCanvas.translate(SIDE, top);
        layout.draw(mCanvas);
        mCanvas.restore();
        int idx = p[0].lastIndexOf(p[1]);
        if (idx >= 0) {
            int line = layout.getLineForOffset(idx);
            mText.setColor(WHITE);
            mCanvas.drawText(p[1], SIDE + layout.getPrimaryHorizontal(idx), top + layout.getLineBaseline(line), mText);
        }
        return top + layout.getHeight() - 6;
    }

    // ---------------------------------------------------------------- звонок

    private String drawCall(State s) {
        boolean blinkOff = s.ringing && s.frame % 2 == 1;
        header(mContext.getString(s.ringing ? R.string.incoming_call : R.string.in_call), CONTENT_TOP + 18,
                s.ringing ? (blinkOff ? GRAY_MUTED : GREEN) : GRAY_MUTED);
        String who = TextUtils.isEmpty(s.caller) ? mContext.getString(R.string.unknown_caller) : s.caller;
        float bottom = wrapped(who, SIDE, CONTENT_TOP + 30, W - 2 * SIDE, 28, true, WHITE, 2);
        if (!TextUtils.isEmpty(s.callerNumber) && !s.callerNumber.equals(who)) {
            text(fit(s.callerNumber, 18, false, W - 2 * SIDE), SIDE, bottom + 26, 18, false, GRAY_DIM, Paint.Align.LEFT);
        }
        if (s.ringing && mPrefs.on(Prefs.LID_ANSWER)) {
            return mContext.getString(R.string.hint_lid_answer);
        }
        return s.inCall && mPrefs.on(Prefs.LID_END) ? mContext.getString(R.string.hint_lid_end) : "";
    }

    // ---------------------------------------------------------------- музыка

    private String drawMusic(State s) {
        header(mContext.getString(s.musicPlaying ? R.string.music_playing : R.string.music_paused), CONTENT_TOP + 18,
                GRAY_MUTED);
        String title = TextUtils.isEmpty(s.musicTitle) ? mContext.getString(R.string.music_nothing) : s.musicTitle;
        float bottom = wrapped(title, SIDE, CONTENT_TOP + 30, W - 2 * SIDE, 24, false, WHITE, 3);
        if (!TextUtils.isEmpty(s.musicArtist)) {
            text(fit(s.musicArtist, 18, false, W - 2 * SIDE), SIDE, bottom + 26, 18, false, GRAY_DIM, Paint.Align.LEFT);
        }
        return sosHint();
    }

    // ---------------------------------------------------------------- уведомления

    /** Как список уведомлений на экране блокировки: заголовок белым над серой строкой. */
    private String drawNotifications(State s) {
        int n = s.notices.size();
        header(n == 0 ? mContext.getString(R.string.notifications_title)
                : mContext.getString(R.string.notifications_title) + " · " + n, CONTENT_TOP + 18, GRAY_MUTED);
        if (n == 0) {
            text(mContext.getString(R.string.no_notifications), SIDE, CONTENT_TOP + 54, 18, false, GRAY_DIM,
                    Paint.Align.LEFT);
            return sosHint();
        }
        boolean showText = mPrefs.on(Prefs.NOTIF_TEXT);
        float y = CONTENT_TOP + 30;
        int shown = Math.min(3, n);
        for (int i = 0; i < shown; i++) {
            Notice item = s.notices.get(i);
            String title = TextUtils.isEmpty(item.title) ? mContext.getString(R.string.new_notification) : item.title;
            String meta = TextUtils.isEmpty(item.app) ? "" : item.app;
            if (showText && !TextUtils.isEmpty(item.text)) {
                meta = meta.isEmpty() ? item.text : meta + " · " + item.text;
            }
            text(fit(title, 18, false, W - 2 * SIDE), SIDE, y + 20, 18, false, i == 0 ? WHITE : GRAY_DIM,
                    Paint.Align.LEFT);
            if (!meta.isEmpty()) {
                text(fit(meta.replace('\n', ' '), 13, false, W - 2 * SIDE), SIDE, y + 38, 13, false, GRAY_MUTED,
                        Paint.Align.LEFT);
            }
            y += 48;
        }
        return n > shown ? mContext.getString(R.string.hint_more, n - shown) + " · " + sosHint() : sosHint();
    }

    // ---------------------------------------------------------------- котик

    /** Котик из букв — моноширинный, как всё остальное. */
    private String drawPet(State s) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean sleeping = mPrefs.isNight() || hour < 6;
        String mood;
        String face;
        if (sleeping) {
            mood = mContext.getString(R.string.pet_sleep);
            face = "( -.- )";
        } else if (s.charging) {
            mood = mContext.getString(R.string.pet_charging);
            face = "( ^.^ )";
        } else if (s.batteryLevel >= 0 && s.batteryLevel <= 15) {
            mood = mContext.getString(R.string.pet_hungry);
            face = "( ;.; )";
        } else if (!s.notices.isEmpty()) {
            mood = mContext.getResources().getQuantityString(R.plurals.pet_notifications,
                    s.notices.size(), s.notices.size());
            face = "( o.o )";
        } else if (s.steps >= 6000) {
            mood = mContext.getString(R.string.pet_steps);
            face = "( ^.^ )";
        } else {
            String[] idle = mContext.getResources().getStringArray(R.array.pet_idle);
            mood = idle[(Calendar.getInstance().get(Calendar.MINUTE) / 5) % idle.length];
            face = s.frame % 7 == 3 ? "( -.- )" : "( o.o )";  // моргает
        }
        Typeface keep = mFace;
        mFace = Typeface.MONOSPACE;  // котик рисуется буквами — только моноширинным
        String[] cat = {" /\\_/\\", face, " > ^ <"};
        float y = CONTENT_TOP + 34;
        for (String line : cat) {
            text(line, SIDE, y, 22, false, WHITE, Paint.Align.LEFT);
            y += 28;
        }
        if (sleeping) {
            text(s.frame % 2 == 0 ? "z Z" : " z Z", SIDE + 100, CONTENT_TOP + 24, 16, false, GRAY_DIM, Paint.Align.LEFT);
        }
        mFace = keep;
        wrapped(mood, SIDE, y - 4, W - 2 * SIDE, 16, false, GRAY_DIM, 3);
        return sosHint();
    }

    // ---------------------------------------------------------------- шар предсказаний

    private String drawEightBall(State s) {
        header(mContext.getString(R.string.page_eightball), CONTENT_TOP + 18, GRAY_MUTED);
        if (s.eightBall == null) {
            wrapped(mContext.getString(R.string.eightball_hint), SIDE, CONTENT_TOP + 34, W - 2 * SIDE, 20, false,
                    GRAY_DIM, 3);
            return mContext.getString(R.string.hint_ask);
        }
        wrapped(s.eightBall, SIDE, CONTENT_TOP + 34, W - 2 * SIDE, 26, false, WHITE, 4);
        return sosHint();
    }

    // ---------------------------------------------------------------- зарядка

    /** Заряд крупно и шкала из клеток, как ползунки в Settings; следующая клетка мигает. */
    private String drawCharging(State s) {
        int level = Math.max(0, s.batteryLevel);
        String state = mContext.getString(!s.charging ? R.string.unplugged
                : level >= 100 ? R.string.charged : R.string.charging);
        header(state, CONTENT_TOP + 6, s.charging ? GREEN : GRAY_MUTED);
        text(level + " %", SIDE - 2, CONTENT_TOP + 84, 64, false, WHITE, Paint.Align.LEFT);
        int cells = 10;
        int filled = Math.min(cells, (level * cells + 50) / 100);
        boolean blink = s.charging && mPrefs.on(Prefs.CHARGING_ANIM) && s.frame % 2 == 0;
        float cell = (W - 2 * SIDE - (cells - 1) * 4) / (float) cells;
        float top = CONTENT_TOP + 108;
        mPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < cells; i++) {
            int color = i < filled ? (s.charging ? GREEN : WHITE) : i == filled && blink ? GRAY_DIM : GRAY_FAINT;
            mPaint.setColor(color);
            float x = SIDE + i * (cell + 4);
            mCanvas.drawRect(x, top, x + cell, top + 22, mPaint);
        }
        return "";
    }

    // ---------------------------------------------------------------- текст

    /** Несколько строк с переносом; возвращает базовую линию последней. */
    private float wrapped(String text, float x, float top, float width, float size, boolean bold, int color,
            int maxLines) {
        mText.setTextSize(size);
        mText.setTypeface(bold ? mBold : mFace);
        mText.setColor(color);
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), mText, (int) width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
        mCanvas.save();
        mCanvas.translate(x, top);
        layout.draw(mCanvas);
        mCanvas.restore();
        return top + layout.getLineBaseline(layout.getLineCount() - 1);
    }

    /** {@code text}, cut with "…" to fit {@code maxWidth}. */
    private String fit(String text, float size, boolean bold, float maxWidth) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size);
        paint.setTypeface(bold ? mBold : mFace);
        paint.setLetterSpacing(mText.getLetterSpacing());
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("…") > maxWidth) {
            end--;
        }
        return text.substring(0, end).trim() + "…";
    }

    /** Рисует строку, возвращает правую границу (для выравнивания влево) или левую (вправо). */
    private float text(String text, float x, float y, float size, boolean bold, int color, Paint.Align align) {
        mText.setTextSize(size);
        mText.setTypeface(bold ? mBold : mFace);
        mText.setColor(color);
        mText.setTextAlign(align);
        mCanvas.drawText(text, x, y, mText);
        float w = mText.measureText(text);
        mText.setTextAlign(Paint.Align.LEFT);
        return align == Paint.Align.LEFT ? x + w : align == Paint.Align.RIGHT ? x - w : x;
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
