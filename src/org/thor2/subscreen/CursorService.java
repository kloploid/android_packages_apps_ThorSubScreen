package org.thor2.subscreen;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Режим курсора для приложений, которыми без касаний не управлять: крестовина водит курсор,
 * OK — касание, долгое OK — долгое касание, у края экрана — прокрутка свайпом.
 *
 * Служба специальных возможностей включена всегда (её включает SubScreenController), но клавиши
 * перехватывает только при включённом курсоре (FLAG_REQUEST_FILTER_KEY_EVENTS ставится на время).
 */
public class CursorService extends AccessibilityService {
    private static final String TAG = "SubScreenCursor";
    private static final long LONG_PRESS_MS = 500;
    private static final int STEP_MIN = 10;
    private static final int STEP_MAX = 48;

    private static CursorService sInstance;
    /** Курсор попросили включить, пока служба ещё не подключилась: включить при подключении. */
    private static volatile boolean sOnWhenConnected;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private WindowManager mWm;
    private CursorView mView;
    private WindowManager.LayoutParams mLp;
    private boolean mEnabled;
    private int mX;
    private int mY;
    private long mEnterDown;

    /**
     * Переключить курсор (из KeyActions, с потока кнопок). Если служба не подключена (не включена
     * в системе), включить её и показать курсор, как только она подключится.
     * Окно курсора создаётся, двигается и убирается только на главном потоке службы — там же
     * приходят onKeyEvent; иначе ViewRootImpl роняет весь процесс (CalledFromWrongThreadException).
     */
    static void toggle(Context context) {
        CursorService s = sInstance;
        if (s == null) {
            Log.i(TAG, "not connected yet: enabling service");
            sOnWhenConnected = true;
            ensureEnabled(context);
            return;
        }
        s.mMain.post(() -> s.setEnabled(!s.mEnabled));
    }

    static String componentName(Context c) {
        return c.getPackageName() + "/" + CursorService.class.getName();
    }

    @Override
    protected void onServiceConnected() {
        sInstance = this;
        mWm = getSystemService(WindowManager.class);
        Log.i(TAG, "connected");
        if (sOnWhenConnected) {
            sOnWhenConnected = false;
            setEnabled(true);
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        setEnabled(false);
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private void setEnabled(boolean enabled) {
        if (mEnabled == enabled) {
            return;
        }
        mEnabled = enabled;
        AccessibilityServiceInfo info = getServiceInfo();
        if (enabled) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        } else {
            info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        }
        setServiceInfo(info);
        if (enabled) {
            Point size = screen();
            mX = size.x / 2;
            mY = size.y / 2;
            mView = new CursorView(this);
            mLp = new WindowManager.LayoutParams(CursorView.SIZE, CursorView.SIZE,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            mLp.gravity = Gravity.TOP | Gravity.START;
            mLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            place();
            mWm.addView(mView, mLp);
        } else if (mView != null) {
            mWm.removeView(mView);
            mView = null;
        }
        Log.i(TAG, "cursor " + (enabled ? "on" : "off"));
    }

    private Point screen() {
        android.graphics.Rect b = mWm.getMaximumWindowMetrics().getBounds();
        return new Point(b.width(), b.height());
    }

    private void place() {
        mLp.x = mX;
        mLp.y = mY;
        if (mView != null && mView.isAttachedToWindow()) {
            mWm.updateViewLayout(mView, mLp);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!mEnabled) {
            return false;
        }
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    move(code, event.getRepeatCount());
                }
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (down && event.getRepeatCount() == 0) {
                    mEnterDown = SystemClock.uptimeMillis();
                } else if (!down) {
                    boolean longPress = SystemClock.uptimeMillis() - mEnterDown >= LONG_PRESS_MS;
                    tap(longPress);
                }
                return true;
            default:
                return false;  // остальные кнопки (Назад, Домой, цифры) — как обычно
        }
    }

    private void move(int code, int repeat) {
        Point size = screen();
        int step = Math.min(STEP_MAX, STEP_MIN + repeat * 4);
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -step : code == KeyEvent.KEYCODE_DPAD_RIGHT ? step : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -step : code == KeyEvent.KEYCODE_DPAD_DOWN ? step : 0;
        int nx = mX + dx;
        int ny = mY + dy;
        // упёрлись в край — прокрутка содержимого в ту же сторону
        if (ny < 0 || ny > size.y - 1 || nx < 0 || nx > size.x - 1) {
            scroll(dx, dy, size);
        }
        mX = Math.max(0, Math.min(size.x - 1, nx));
        mY = Math.max(0, Math.min(size.y - 1, ny));
        place();
    }

    private void scroll(int dx, int dy, Point size) {
        float cx = size.x / 2f;
        float cy = size.y / 2f;
        float lenY = size.y * 0.35f;
        float lenX = size.x * 0.35f;
        Path p = new Path();
        // «вниз» у нижнего края: палец ведём снизу вверх (содержимое уезжает вверх)
        p.moveTo(cx + Math.signum(dx) * lenX / 2, cy + Math.signum(dy) * lenY / 2);
        p.lineTo(cx - Math.signum(dx) * lenX / 2, cy - Math.signum(dy) * lenY / 2);
        gesture(p, 250);
    }

    private void tap(boolean longPress) {
        Path p = new Path();
        p.moveTo(mX, mY);
        gesture(p, longPress ? 700 : 50);
    }

    private void gesture(Path path, long durationMs) {
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build();
        dispatchGesture(g, null, null);
    }

    /**
     * Включить службу в системе. Вызывается при старте, после разблокировки (при раннем старте
     * persistent-приложения запись не сохранялась) и при нажатии кнопки курсора.
     */
    static void ensureEnabled(Context context) {
        String me = componentName(context);
        String list = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list != null && list.contains(me)) {
            return;
        }
        String updated = list == null || list.isEmpty() ? me : list + ":" + me;
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    /** Стрелка-курсор; острие — в левом верхнем углу окна (= точка касания). */
    private static final class CursorView extends View {
        static final int SIZE = 36;
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mArrow = new Path();

        CursorView(Context c) {
            super(c);
            mFill.setColor(Color.WHITE);
            mStroke.setColor(Color.BLACK);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(3);
            mArrow.moveTo(2, 2);
            mArrow.lineTo(2, 30);
            mArrow.lineTo(10, 23);
            mArrow.lineTo(16, 34);
            mArrow.lineTo(21, 32);
            mArrow.lineTo(15, 21);
            mArrow.lineTo(25, 21);
            mArrow.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(mArrow, mFill);
            canvas.drawPath(mArrow, mStroke);
        }
    }
}
