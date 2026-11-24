package org.thor2.subscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Рисует кадр внешнего экрана (альбомный {@link Panel#WIDTH}×{@link Panel#HEIGHT}). */
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
        /** Короткая строка поверх (например, «Фонарик включён»). */
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
    private static final int TOP = 32;  // высота строки состояния
    private static final int GREY = Color.rgb(165, 165, 165);
    private static final int DIM = Color.rgb(90, 90, 90);
    private static final int GREEN = Color.rgb(80, 220, 120);
    private static final int RED = Color.rgb(255, 80, 70);

    private final Context mContext;
    private final Prefs mPrefs;
    private final Bitmap mBitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas mCanvas = new Canvas(mBitmap);
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap mPhoto;
    private long mPhotoStamp = -1;
    private int mAccent;

    Renderer(Context context, Prefs prefs) {
        mContext = context;
        mPrefs = prefs;
    }

    Bitmap draw(State s) {
        mAccent = mPrefs.accent();
        mPaint.reset();
        mPaint.setAntiAlias(true);
        drawBackground();
        switch (s.page) {
            case CALL: drawCall(s); break;
            case MUSIC: drawMusic(s); break;
            case NOTIFICATIONS: drawNotifications(s); break;
            case PET: drawPet(s); break;
            case EIGHTBALL: drawEightBall(s); break;
            case CHARGING: drawCharging(s); break;
            default: drawClock(s); break;
        }
        if (s.page != Page.CHARGING) {
            drawStatusBar(s);
        }
        if (!TextUtils.isEmpty(s.toast)) {
            drawToast(s.toast);
        }
        return mBitmap;
    }

    // ---------------------------------------------------------------- фон

    private void drawBackground() {
        mCanvas.drawColor(Color.BLACK);
        String bg = mPrefs.background();
        if ("gradient".equals(bg)) {
            int top = Color.argb(150, Color.red(mAccent), Color.green(mAccent), Color.blue(mAccent));
            mPaint.setShader(new LinearGradient(0, 0, 0, H, top, Color.BLACK, Shader.TileMode.CLAMP));
            mCanvas.drawRect(0, 0, W, H, mPaint);
            mPaint.setShader(null);
        } else if ("photo".equals(bg)) {
            Bitmap photo = photo();
            if (photo != null) {
                mCanvas.drawBitmap(photo, 0, 0, null);
                mCanvas.drawColor(Color.argb(110, 0, 0, 0));  // затемнение, чтобы читался текст
            }
        }
    }

    private Bitmap photo() {
        File f = new File(Prefs.storage(mContext).getFilesDir(), Prefs.PHOTO_FILE);
        long stamp = f.exists() ? f.lastModified() : 0;
        if (stamp != mPhotoStamp) {
            mPhotoStamp = stamp;
            mPhoto = stamp == 0 ? null : BitmapFactory.decodeFile(f.getPath());
        }
        return mPhoto;
    }

    // ---------------------------------------------------------------- строка состояния

    private void drawStatusBar(State s) {
        float x = 10;
        if (mPrefs.on(Prefs.MISSED) && s.missedCalls > 0) {
            x = text("📞" + s.missedCalls, x, 24, 18, false, RED, Paint.Align.LEFT) + 10;
        }
        if (mPrefs.on(Prefs.NOTIF_ICONS)) {
            int shown = 0;
            for (Notice n : s.notices) {
                if (n.icon == null || shown == 5) {
                    continue;
                }
                n.icon.setBounds((int) x, 8, (int) x + 18, 26);
                n.icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
                n.icon.draw(mCanvas);
                x += 24;
                shown++;
            }
            if (s.notices.size() > shown && shown == 5) {
                text("+" + (s.notices.size() - shown), x, 24, 16, false, GREY, Paint.Align.LEFT);
            }
        }
        drawBattery(s, W - 10, 9);
    }

    /** Батарея, правый край — {@code right}, верх — {@code top}. */
    private void drawBattery(State s, float right, float top) {
        String style = mPrefs.batteryStyle();
        if ("hidden".equals(style) || s.batteryLevel < 0) {
            return;
        }
        float x = right;
        if (!"percent".equals(style)) {
            float w = 30, h = 15;
            RectF body = new RectF(x - w, top, x - 3, top + h);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(2);
            mPaint.setColor(Color.WHITE);
            mCanvas.drawRoundRect(body, 3, 3, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            mCanvas.drawRect(x - 3, top + 4, x, top + h - 4, mPaint);
            int color = s.charging ? GREEN : s.batteryLevel <= 15 ? RED : Color.WHITE;
            mPaint.setColor(color);
            float fill = (body.width() - 6) * s.batteryLevel / 100f;
            mCanvas.drawRect(body.left + 3, body.top + 3, body.left + 3 + fill, body.bottom - 3, mPaint);
            if (s.charging) {
                drawBolt(body.centerX(), body.centerY(), 7, Color.BLACK);
            }
            x = body.left - 6;
        }
        if (!"icon".equals(style)) {
            text(s.batteryLevel + "%", x, top + 14, 18, false, Color.WHITE, Paint.Align.RIGHT);
        }
    }

    private void drawBolt(float cx, float cy, float r, int color) {
        Path p = new Path();
        p.moveTo(cx + r * 0.2f, cy - r);
        p.lineTo(cx - r * 0.6f, cy + r * 0.15f);
        p.lineTo(cx - r * 0.05f, cy + r * 0.15f);
        p.lineTo(cx - r * 0.25f, cy + r);
        p.lineTo(cx + r * 0.6f, cy - r * 0.15f);
        p.lineTo(cx + r * 0.05f, cy - r * 0.15f);
        p.close();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);
        mCanvas.drawPath(p, mPaint);
    }

    // ---------------------------------------------------------------- часы

    private boolean is24() {
        Boolean f = mPrefs.force24h();
        return f != null ? f : DateFormat.is24HourFormat(mContext);
    }

    private void drawClock(State s) {
        Calendar c = Calendar.getInstance();
        switch (mPrefs.clockStyle()) {
            case "stack": drawStackClock(c); break;
            case "analog": drawAnalogClock(c); break;
            case "words": drawWordClock(c); break;
            case "binary": drawBinaryClock(c); break;
            default: drawDigitalClock(c); break;
        }
        drawFooter(s);
    }

    private String hhmm(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (!is24()) {
            h = h % 12 == 0 ? 12 : h % 12;
            return h + ":" + two(c.get(Calendar.MINUTE));
        }
        return two(h) + ":" + two(c.get(Calendar.MINUTE));
    }

    private String ampm(Calendar c) {
        return is24() ? null : (c.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM");
    }

    private String dateLine() {
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        return DateFormat.format(pattern, new Date()).toString();
    }

    private void drawDigitalClock(Calendar c) {
        boolean sec = mPrefs.seconds();
        String time = hhmm(c);
        float size = sec ? 74 : 84;
        float cx = W / 2f;
        mText.setTextSize(size);
        mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float timeW = mText.measureText(time);
        String tail = sec ? ":" + two(c.get(Calendar.SECOND)) : ampm(c);
        float tailW = 0;
        if (tail != null) {
            mText.setTextSize(sec ? 34 : 24);
            tailW = mText.measureText(tail) + 4;
        }
        float left = cx - (timeW + tailW) / 2;
        text(time, left, 126, size, "sans-serif-medium", mAccent, Paint.Align.LEFT);
        if (tail != null) {
            text(tail, left + timeW + 4, 126, sec ? 34 : 24, "sans-serif-medium", GREY, Paint.Align.LEFT);
        }
        if (mPrefs.date()) {
            text(dateLine(), cx, 162, 22, false, GREY, Paint.Align.CENTER);
        }
    }

    private void drawStackClock(Calendar c) {
        String t = hhmm(c);
        String[] hm = t.split(":");
        Typeface bold = Typeface.create("sans-serif-black", Typeface.NORMAL);
        text(hm[0], 18, 118, 92, bold, mAccent, Paint.Align.LEFT);
        text(hm[1], 18, 206, 92, bold, Color.WHITE, Paint.Align.LEFT);
        if (mPrefs.date()) {
            String day = DateFormat.format("EEEE", new Date()).toString();
            String date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"),
                    new Date()).toString();
            text(cap(day), W - 14, 100, 22, false, GREY, Paint.Align.RIGHT);
            text(date, W - 14, 130, 26, true, Color.WHITE, Paint.Align.RIGHT);
        }
        String am = ampm(c);
        if (am != null) {
            text(am, W - 14, 160, 20, false, GREY, Paint.Align.RIGHT);
        }
    }

    private void drawAnalogClock(Calendar c) {
        float cx = W / 2f, cy = 128, r = 88;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(2);
        mPaint.setColor(DIM);
        mCanvas.drawCircle(cx, cy, r, mPaint);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 60; i++) {
            boolean hour = i % 5 == 0;
            double a = Math.toRadians(i * 6);
            float r1 = r - (hour ? 12 : 5);
            mPaint.setStrokeWidth(hour ? 4 : 1.5f);
            mPaint.setColor(hour ? Color.WHITE : DIM);
            mCanvas.drawLine(cx + (float) Math.sin(a) * r1, cy - (float) Math.cos(a) * r1,
                    cx + (float) Math.sin(a) * (r - 2), cy - (float) Math.cos(a) * (r - 2), mPaint);
        }
        float min = c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f;
        float hour = c.get(Calendar.HOUR) + min / 60f;
        hand(cx, cy, hour * 30, r * 0.52f, 7, Color.WHITE);
        hand(cx, cy, min * 6, r * 0.8f, 5, mAccent);
        if (mPrefs.seconds()) {
            hand(cx, cy, c.get(Calendar.SECOND) * 6, r * 0.86f, 2, RED);
        }
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mAccent);
        mCanvas.drawCircle(cx, cy, 6, mPaint);
        if (mPrefs.date()) {
            text(cap(DateFormat.format("EEE", new Date()).toString()), 12, 120, 22, false, GREY, Paint.Align.LEFT);
            text(DateFormat.format("d", new Date()).toString(), W - 12, 124, 30, true, Color.WHITE,
                    Paint.Align.RIGHT);
        }
    }

    private void hand(float cx, float cy, float deg, float len, float width, int color) {
        double a = Math.toRadians(deg);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeWidth(width);
        mPaint.setColor(color);
        mCanvas.drawLine(cx, cy, cx + (float) Math.sin(a) * len, cy - (float) Math.cos(a) * len, mPaint);
    }

    private void drawWordClock(Calendar c) {
        boolean ru = "ru".equals(Locale.getDefault().getLanguage());
        String[] p = WordClock.phrase(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), ru);
        mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mText.setColor(Color.WHITE);
        float size = 34;
        StaticLayout layout;
        do {
            mText.setTextSize(size);
            layout = StaticLayout.Builder.obtain(p[0], 0, p[0].length(), mText, W - 28)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            size -= 2;
        } while (layout.getHeight() > 150 && size > 18);
        // слово-час — акцентным цветом: рисуем всё белым, затем поверх только его
        float top = 36 + (160 - layout.getHeight()) / 2f;
        mCanvas.save();
        mCanvas.translate(14, top);
        layout.draw(mCanvas);
        mCanvas.restore();
        int idx = p[0].lastIndexOf(p[1]);
        if (idx >= 0) {
            int line = layout.getLineForOffset(idx);
            float x = 14 + layout.getPrimaryHorizontal(idx);
            float y = top + layout.getLineBaseline(line);
            mText.setColor(mAccent);
            mCanvas.drawText(p[1], x, y, mText);
        }
        if (mPrefs.date()) {
            text(dateLine(), W / 2f, 210, 18, false, GREY, Paint.Align.CENTER);
        }
    }

    private void drawBinaryClock(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);
        int[] digits = {h / 10, h % 10, m / 10, m % 10};
        float dot = 15, gap = 40, x0 = W / 2f - gap * 1.5f - 14, y0 = 60;
        for (int col = 0; col < 4; col++) {
            float x = x0 + col * gap + (col >= 2 ? 28 : 0);
            for (int bit = 3; bit >= 0; bit--) {
                boolean set = (digits[col] >> bit & 1) == 1;
                mPaint.setStyle(set ? Paint.Style.FILL : Paint.Style.STROKE);
                mPaint.setStrokeWidth(2);
                mPaint.setColor(set ? mAccent : DIM);
                mCanvas.drawCircle(x, y0 + (3 - bit) * 34, dot / 1.2f + 2, mPaint);
            }
            text(String.valueOf(digits[col]), x, y0 + 4 * 34 + 2, 18, false, GREY, Paint.Align.CENTER);
        }
    }

    /** Нижняя строка часов: подпись, шаги. */
    private void drawFooter(State s) {
        List<String> parts = new ArrayList<>();
        String sig = mPrefs.signature();
        if (!sig.isEmpty()) {
            parts.add(sig);
        }
        if (mPrefs.on(Prefs.STEPS) && s.steps >= 0 && !"words".equals(mPrefs.clockStyle())) {
            parts.add("👣 " + s.steps);
        }
        if (parts.isEmpty() || "stack".equals(mPrefs.clockStyle())) {
            if ("stack".equals(mPrefs.clockStyle()) && !parts.isEmpty()) {
                text(fit(TextUtils.join("  ", parts), 18, false, 110), W - 14, 200, 18, false, mAccent,
                        Paint.Align.RIGHT);
            }
            return;
        }
        text(fit(TextUtils.join("   ", parts), 20, false, W - 24), W / 2f, 222, 20, false, mAccent,
                Paint.Align.CENTER);
    }

    // ---------------------------------------------------------------- звонок

    private void drawCall(State s) {
        String title = mContext.getString(s.ringing ? R.string.incoming_call : R.string.in_call);
        text("📞 " + title, W / 2f, 74, 22, false, s.ringing ? GREEN : GREY, Paint.Align.CENTER);
        String who = TextUtils.isEmpty(s.caller) ? mContext.getString(R.string.unknown_caller) : s.caller;
        text(fit(who, 38, true, W - 20), W / 2f, 136, 38, true, Color.WHITE, Paint.Align.CENTER);
        if (!TextUtils.isEmpty(s.callerNumber) && !s.callerNumber.equals(who)) {
            text(s.callerNumber, W / 2f, 172, 20, false, GREY, Paint.Align.CENTER);
        }
        if (s.ringing && s.frame % 2 == 0) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(3);
            mPaint.setColor(GREEN);
            mCanvas.drawRoundRect(new RectF(4, TOP + 4, W - 4, H - 4), 18, 18, mPaint);
        }
    }

    // ---------------------------------------------------------------- музыка

    private void drawMusic(State s) {
        float artSize = 112, ax = 14, ay = 52;
        RectF art = new RectF(ax, ay, ax + artSize, ay + artSize);
        if (s.musicArt != null) {
            Bitmap scaled = Bitmap.createScaledBitmap(s.musicArt, (int) artSize, (int) artSize, true);
            mPaint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            mCanvas.save();
            mCanvas.translate(ax, ay);
            mCanvas.drawRoundRect(new RectF(0, 0, artSize, artSize), 14, 14, mPaint);
            mCanvas.restore();
            mPaint.setShader(null);
        } else {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(Color.rgb(40, 40, 40));
            mCanvas.drawRoundRect(art, 14, 14, mPaint);
            text("🎵", art.centerX(), art.centerY() + 18, 48, false, Color.WHITE, Paint.Align.CENTER);
        }
        float tx = ax + artSize + 14, tw = W - tx - 10;
        drawWrapped(TextUtils.isEmpty(s.musicTitle) ? "—" : s.musicTitle, tx, 60, tw, 22, true,
                Color.WHITE, 3);
        if (!TextUtils.isEmpty(s.musicArtist)) {
            text(fit(s.musicArtist, 18, false, tw), tx, 160, 18, false, GREY, Paint.Align.LEFT);
        }
        // кнопка состояния
        float cx = W / 2f, cy = 202;
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mAccent);
        mCanvas.drawCircle(cx, cy, 20, mPaint);
        mPaint.setColor(Color.BLACK);
        if (s.musicPlaying) {
            mCanvas.drawRect(cx - 8, cy - 9, cx - 3, cy + 9, mPaint);
            mCanvas.drawRect(cx + 3, cy - 9, cx + 8, cy + 9, mPaint);
        } else {
            Path p = new Path();
            p.moveTo(cx - 6, cy - 10);
            p.lineTo(cx + 10, cy);
            p.lineTo(cx - 6, cy + 10);
            p.close();
            mCanvas.drawPath(p, mPaint);
        }
    }

    // ---------------------------------------------------------------- уведомления

    private void drawNotifications(State s) {
        if (s.notices.isEmpty()) {
            text(mContext.getString(R.string.no_notifications), W / 2f, 136, 22, false, GREY,
                    Paint.Align.CENTER);
            return;
        }
        boolean showText = mPrefs.on(Prefs.NOTIF_TEXT);
        float y = TOP + 8;
        int count = Math.min(3, s.notices.size());
        float rowH = (H - TOP - 8) / 3f;
        for (int i = 0; i < count; i++) {
            Notice n = s.notices.get(i);
            if (n.icon != null) {
                n.icon.setBounds(12, (int) y + 6, 40, (int) y + 34);
                n.icon.setColorFilter(new PorterDuffColorFilter(mAccent, PorterDuff.Mode.SRC_IN));
                n.icon.draw(mCanvas);
            }
            text(fit(n.app == null ? "" : n.app, 15, false, W - 60), 52, y + 16, 15, false, GREY,
                    Paint.Align.LEFT);
            String main = showText && !TextUtils.isEmpty(n.text)
                    ? (TextUtils.isEmpty(n.title) ? n.text : n.title + ": " + n.text)
                    : (TextUtils.isEmpty(n.title) ? mContext.getString(R.string.new_notification) : n.title);
            text(fit(main, 20, true, W - 62), 52, y + 40, 20, true, Color.WHITE, Paint.Align.LEFT);
            y += rowH;
        }
        if (s.notices.size() > 3) {
            text("+" + (s.notices.size() - 3), W - 10, H - 8, 15, false, GREY, Paint.Align.RIGHT);
        }
    }

    // ---------------------------------------------------------------- котик

    private void drawPet(State s) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean sleeping = mPrefs.isNight() || hour < 6;
        String mood;
        int eyes;  // 0 открыты, 1 закрыты, 2 счастливые ^^, 3 грустные
        if (sleeping) {
            mood = mContext.getString(R.string.pet_sleep);
            eyes = 1;
        } else if (s.charging) {
            mood = mContext.getString(R.string.pet_charging);
            eyes = 2;
        } else if (s.batteryLevel >= 0 && s.batteryLevel <= 15) {
            mood = mContext.getString(R.string.pet_hungry);
            eyes = 3;
        } else if (!s.notices.isEmpty()) {
            mood = mContext.getResources().getQuantityString(R.plurals.pet_notifications,
                    s.notices.size(), s.notices.size());
            eyes = 0;
        } else if (s.steps >= 6000) {
            mood = mContext.getString(R.string.pet_steps);
            eyes = 2;
        } else {
            String[] idle = mContext.getResources().getStringArray(R.array.pet_idle);
            mood = idle[(Calendar.getInstance().get(Calendar.MINUTE) / 5) % idle.length];
            eyes = s.frame % 7 == 3 ? 1 : 0;  // моргает
        }
        float cx = 82, cy = 142;
        int fur = mAccent == Color.WHITE ? Color.rgb(255, 170, 70) : mAccent;
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(fur);
        // уши
        Path ears = new Path();
        ears.moveTo(cx - 52, cy - 22);
        ears.lineTo(cx - 44, cy - 76);
        ears.lineTo(cx - 12, cy - 46);
        ears.close();
        ears.moveTo(cx + 52, cy - 22);
        ears.lineTo(cx + 44, cy - 76);
        ears.lineTo(cx + 12, cy - 46);
        ears.close();
        mCanvas.drawPath(ears, mPaint);
        mCanvas.drawOval(new RectF(cx - 62, cy - 56, cx + 62, cy + 52), mPaint);
        // глаза
        mPaint.setColor(Color.BLACK);
        mPaint.setStrokeWidth(4);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        for (int side = -1; side <= 1; side += 2) {
            float ex = cx + side * 24, ey = cy - 10;
            switch (eyes) {
                case 1:
                    mPaint.setStyle(Paint.Style.STROKE);
                    mCanvas.drawLine(ex - 9, ey, ex + 9, ey, mPaint);
                    break;
                case 2:
                    mPaint.setStyle(Paint.Style.STROKE);
                    mCanvas.drawLine(ex - 9, ey + 4, ex, ey - 5, mPaint);
                    mCanvas.drawLine(ex, ey - 5, ex + 9, ey + 4, mPaint);
                    break;
                case 3:
                    mPaint.setStyle(Paint.Style.FILL);
                    mCanvas.drawCircle(ex, ey + 2, 7, mPaint);
                    mPaint.setStyle(Paint.Style.STROKE);
                    mCanvas.drawLine(ex - 10, ey - 12 + side * 0, ex + 8, ey - 8 - side * 3, mPaint);
                    break;
                default:
                    mPaint.setStyle(Paint.Style.FILL);
                    mCanvas.drawCircle(ex, ey, 9, mPaint);
                    mPaint.setColor(Color.WHITE);
                    mCanvas.drawCircle(ex + 3, ey - 3, 3, mPaint);
                    mPaint.setColor(Color.BLACK);
                    break;
            }
        }
        // нос, рот, усы
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(Color.rgb(255, 120, 150));
        mCanvas.drawCircle(cx, cy + 10, 5, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(2.5f);
        mPaint.setColor(Color.BLACK);
        mCanvas.drawArc(new RectF(cx - 12, cy + 8, cx, cy + 22), 0, 180, false, mPaint);
        mCanvas.drawArc(new RectF(cx, cy + 8, cx + 12, cy + 22), 0, 180, false, mPaint);
        mPaint.setColor(Color.WHITE);
        mPaint.setStrokeWidth(1.5f);
        for (int side = -1; side <= 1; side += 2) {
            mCanvas.drawLine(cx + side * 20, cy + 12, cx + side * 66, cy + 4, mPaint);
            mCanvas.drawLine(cx + side * 20, cy + 18, cx + side * 66, cy + 22, mPaint);
        }
        if (sleeping) {
            float z = s.frame % 3 * 6;
            text("z", cx + 52, cy - 50 - z, 18, true, Color.WHITE, Paint.Align.LEFT);
            text("Z", cx + 64, cy - 66 - z, 24, true, Color.WHITE, Paint.Align.LEFT);
        }
        // облачко с репликой
        RectF bubble = new RectF(160, 50, W - 8, 168);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(Color.rgb(35, 35, 35));
        mCanvas.drawRoundRect(bubble, 16, 16, mPaint);
        Path tail = new Path();
        tail.moveTo(bubble.left, 120);
        tail.lineTo(bubble.left - 16, 132);
        tail.lineTo(bubble.left, 136);
        tail.close();
        mCanvas.drawPath(tail, mPaint);
        drawWrapped(mood, bubble.left + 10, bubble.top + 12, bubble.width() - 20, 18, false, Color.WHITE, 5);
        if (mPrefs.on(Prefs.STEPS) && s.steps >= 0) {
            text("👣 " + s.steps, bubble.centerX(), 206, 20, false, mAccent, Paint.Align.CENTER);
        }
    }

    // ---------------------------------------------------------------- шар предсказаний

    private void drawEightBall(State s) {
        float cx = W / 2f, cy = 136, r = 92;
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, Color.rgb(70, 70, 80),
                Color.BLACK, Shader.TileMode.CLAMP));
        mCanvas.drawCircle(cx, cy, r, mPaint);
        mPaint.setShader(null);
        if (s.eightBall == null) {
            mPaint.setColor(Color.WHITE);
            mCanvas.drawCircle(cx, cy, 42, mPaint);
            text("8", cx, cy + 22, 64, true, Color.BLACK, Paint.Align.CENTER);
            text(mContext.getString(R.string.eightball_hint), cx, H - 6, 15, false, GREY, Paint.Align.CENTER);
            return;
        }
        Path tri = new Path();
        tri.moveTo(cx, cy + 60);
        tri.lineTo(cx - 70, cy - 46);
        tri.lineTo(cx + 70, cy - 46);
        tri.close();
        mPaint.setColor(Color.rgb(30, 60, 200));
        mCanvas.drawPath(tri, mPaint);
        drawWrapped(s.eightBall, cx - 52, cy - 38, 104, 17, true, Color.WHITE, 4, true);
    }

    // ---------------------------------------------------------------- зарядка

    private void drawCharging(State s) {
        int level = Math.max(0, s.batteryLevel);
        RectF body = new RectF(52, 62, W - 64, 152);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(5);
        mPaint.setColor(Color.WHITE);
        mCanvas.drawRoundRect(body, 16, 16, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
        mCanvas.drawRoundRect(new RectF(body.right + 4, body.centerY() - 18, body.right + 14,
                body.centerY() + 18), 4, 4, mPaint);
        // «жидкость» с волной
        RectF inner = new RectF(body.left + 8, body.top + 8, body.right - 8, body.bottom - 8);
        float fillRight = inner.left + inner.width() * level / 100f;
        int color = level <= 15 ? RED : s.charging ? GREEN : mAccent;
        mPaint.setColor(color);
        Path wave = new Path();
        wave.moveTo(inner.left, inner.bottom);
        wave.lineTo(inner.left, inner.top);
        float phase = s.frame * 0.9f;
        for (float y = inner.top; y <= inner.bottom; y += 4) {
            float dx = (float) Math.sin(y / 9f + phase) * (level >= 100 ? 0 : 5);
            wave.lineTo(Math.min(inner.right, fillRight + dx), y);
        }
        wave.lineTo(inner.left, inner.bottom);
        wave.close();
        mCanvas.save();
        mCanvas.clipRect(inner);
        mCanvas.drawPath(wave, mPaint);
        mCanvas.restore();
        if (s.charging) {
            drawBolt(body.centerX(), body.centerY(), 26, Color.WHITE);
        }
        text(level + "%", W / 2f, 206, 44, true, Color.WHITE, Paint.Align.CENTER);
        String sub = mContext.getString(!s.charging ? R.string.unplugged
                : level >= 100 ? R.string.charged : R.string.charging);
        text(sub, W / 2f, 232, 17, false, GREY, Paint.Align.CENTER);
    }

    private void drawToast(String toast) {
        mText.setTextSize(18);
        mText.setTypeface(Typeface.DEFAULT_BOLD);
        float w = Math.min(W - 20, mText.measureText(toast) + 32);
        RectF pill = new RectF(W / 2f - w / 2, H - 42, W / 2f + w / 2, H - 8);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(Color.rgb(40, 40, 40));
        mCanvas.drawRoundRect(pill, 17, 17, mPaint);
        text(fit(toast, 18, true, w - 20), W / 2f, H - 19, 18, true, mAccent, Paint.Align.CENTER);
    }

    // ---------------------------------------------------------------- текст

    private void drawWrapped(String text, float x, float y, float width, float size, boolean bold, int color,
            int maxLines) {
        drawWrapped(text, x, y, width, size, bold, color, maxLines, false);
    }

    private void drawWrapped(String text, float x, float y, float width, float size, boolean bold, int color,
            int maxLines, boolean center) {
        mText.setTextSize(size);
        mText.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        mText.setColor(color);
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), mText, (int) width)
                .setAlignment(center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
        mCanvas.save();
        mCanvas.translate(x, y);
        layout.draw(mCanvas);
        mCanvas.restore();
    }

    private String fit(String text, float size, boolean bold, float maxWidth) {
        TextPaint paint = new TextPaint(mText);
        paint.setTextSize(size);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString();
    }

    /** Рисует строку, возвращает правую границу (для выравнивания влево). */
    private float text(String text, float x, float y, float size, boolean bold, int color, Paint.Align align) {
        return text(text, x, y, size, bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT, color, align);
    }

    private float text(String text, float x, float y, float size, String family, int color, Paint.Align align) {
        return text(text, x, y, size, Typeface.create(family, Typeface.NORMAL), color, align);
    }

    private float text(String text, float x, float y, float size, Typeface face, int color, Paint.Align align) {
        mText.setTextSize(size);
        mText.setTypeface(face);
        mText.setColor(color);
        mText.setTextAlign(align);
        mCanvas.drawText(text, x, y, mText);
        float w = mText.measureText(text);
        mText.setTextAlign(Paint.Align.LEFT);
        return align == Paint.Align.LEFT ? x + w : x;
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
