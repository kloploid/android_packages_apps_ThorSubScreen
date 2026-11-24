package org.thor2.subscreen;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.UserManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * Логика внешнего экрана: когда показывать, какую страницу, когда гасить.
 *
 * Крышка: закрытие = KEYCODE_SLEEP (экран гаснет с причиной GO_TO_SLEEP_REASON_SLEEP_BUTTON),
 * открытие = KEYCODE_WAKEUP. Положение крышки — датчик Холла /dev/hall_gpio (0 = закрыта).
 * Кнопки SOS и громкости при погашенном экране присылает PhoneWindowManager (патч
 * frameworks_base 0001) широковещательным {@link #ACTION_KEY}.
 *
 * Всё состояние меняется только в потоке {@link #mHandler}.
 */
final class SubScreenController implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "SubScreen";

    static final String ACTION_KEY = "org.thor2.subscreen.action.KEY";
    static final String EXTRA_KEYCODE = "org.thor2.subscreen.extra.KEYCODE";
    static final String EXTRA_ACTION = "org.thor2.subscreen.extra.ACTION";
    static final String EXTRA_SOURCE = "org.thor2.subscreen.extra.SOURCE";

    private static final long DOUBLE_PRESS_MS = 450;
    private static final long TOAST_MS = 2500;
    private static final long CHARGING_FRAME_MS = 250;
    private static final long ANIM_FRAME_MS = 700;

    private static final String HALL_DEV = "/dev/hall_gpio";
    /** _IOR('H', 1, int) из mtk-kpd: уровень GPIO датчика Холла. */
    private static final int HALL_IOC_GET = 0x80044801;

    private static SubScreenController sInstance;

    private final Context mContext;
    private final Handler mHandler;
    private final PowerManager mPowerManager;
    private final PowerManager.WakeLock mWakeLock;
    private final Panel mPanel = new Panel();
    private final Prefs mPrefs;
    private final Renderer mRenderer;
    private final Renderer.State mState = new Renderer.State();
    private final KeyActions mKeyActions;
    private final Random mRandom = new Random();
    private final Runnable mOffRunnable = this::hide;
    private final Runnable mTick = this::tick;
    private final Runnable mClearToast = () -> {
        mState.toast = null;
        redraw();
    };

    private boolean mLidClosedByEvent;
    private boolean mShown;
    private Runnable mPendingSos;

    private SensorManager mSensors;
    private Sensor mStepSensor;
    private float mStepsTotal = -1;

    private MediaSessionManager mMedia;
    private MediaController mMediaController;

    private CameraManager mCameras;
    private String mTorchCamera;
    private boolean mTorchOn;

    static synchronized SubScreenController get(Context context) {
        if (sInstance == null) {
            sInstance = new SubScreenController(context.getApplicationContext());
        }
        return sInstance;
    }

    private SubScreenController(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread("subscreen");
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mPowerManager = context.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubScreen:show");
        mWakeLock.setReferenceCounted(false);
        mPrefs = new Prefs(context);
        mRenderer = new Renderer(context, mPrefs);
        mKeyActions = new KeyActions(context, mHandler, mPrefs, () -> toggleTorch(false));
    }

    Prefs prefs() {
        return mPrefs;
    }

    // ================================================================ запуск

    void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiver(mReceiver, filter, null, mHandler, Context.RECEIVER_EXPORTED);
        // Кнопки от PhoneWindowManager (system_server, тот же uid system).
        mContext.registerReceiver(mKeyReceiver, new IntentFilter(ACTION_KEY), null, mHandler,
                Context.RECEIVER_NOT_EXPORTED);
        mPrefs.raw().registerOnSharedPreferenceChangeListener(this);
        mHandler.post(() -> {
            CursorService.ensureEnabled(mContext);
            enableNotificationListener();
            initSteps();
            initMedia();
            initTorch();
            updateMissedCalls();
            boolean closed = isLidClosed();
            Log.i(TAG, "started, hall=" + readHall() + " lidClosed=" + closed);
            // Сразу после загрузки на панели висит картинка загрузчика: заменить или погасить.
            if (closed && mPrefs.enabled()) {
                wake(Renderer.Page.CLOCK, mPrefs.timeoutMs());
            } else {
                mPanel.forceOff();
            }
        });
    }

    // ================================================================ события

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    onScreenOff();
                    break;
                case Intent.ACTION_SCREEN_ON:
                    if (!isLidClosed()) {
                        mLidClosedByEvent = false;
                        hide();
                    }
                    break;
                case Intent.ACTION_BATTERY_CHANGED:
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    mState.batteryLevel = level < 0 || scale <= 0 ? -1 : level * 100 / scale;
                    int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    mState.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
                    redraw();
                    break;
                case Intent.ACTION_POWER_CONNECTED:
                case Intent.ACTION_POWER_DISCONNECTED:
                    mState.charging = Intent.ACTION_POWER_CONNECTED.equals(intent.getAction());
                    if (mPrefs.wake(Prefs.WAKE_CHARGING) && isLidClosed()) {
                        wake(mPrefs.on(Prefs.CHARGING_ANIM) ? Renderer.Page.CHARGING : Renderer.Page.CLOCK,
                                mPrefs.timeoutMs());
                    }
                    break;
                case TelephonyManager.ACTION_PHONE_STATE_CHANGED:
                    onPhoneState(intent);
                    break;
                case Intent.ACTION_USER_UNLOCKED:
                    CursorService.ensureEnabled(mContext);
                    enableNotificationListener();
                    initMedia();
                    updateMissedCalls();
                    break;
            }
        }
    };

    /** Нажатия от PhoneWindowManager (патчи frameworks_base 0001, 0002). */
    private final BroadcastReceiver mKeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int keyCode = intent.getIntExtra(EXTRA_KEYCODE, 0);
            switch (intent.getIntExtra(EXTRA_SOURCE, KeyActions.SOURCE_SCREEN_OFF)) {
                case KeyActions.SOURCE_UNHANDLED:
                    mKeyActions.onUnhandled(keyCode, intent.getIntExtra(EXTRA_ACTION, KeyEvent.ACTION_DOWN));
                    break;
                case KeyActions.SOURCE_LID:
                    mKeyActions.onLid(keyCode);
                    break;
                default:
                    onKey(keyCode);
                    break;
            }
        }
    };

    private void onScreenOff() {
        int reason = mPowerManager.getLastSleepReason();
        Log.d(TAG, "screen off, reason=" + reason + " hall=" + readHall());
        if (reason != PowerManager.GO_TO_SLEEP_REASON_SLEEP_BUTTON) {
            return;
        }
        // Экран погасила клавиша SLEEP — значит, крышку закрыли.
        mLidClosedByEvent = true;
        Integer hall = readHall();
        if (hall != null) {
            mPrefs.raw().edit().putInt(Prefs.HALL_CLOSED, hall).apply();
        }
        updateMissedCalls();
        if (mPrefs.wake(Prefs.WAKE_LID)) {
            wake(Renderer.Page.CLOCK, mPrefs.timeoutMs());
        } else {
            hide();
        }
    }

    private void onKey(int keyCode) {
        Log.d(TAG, "key " + keyCode + " shown=" + mShown);
        if (!mPrefs.enabled()) {
            return;
        }
        if (keyCode == KeyEvent.KEYCODE_F4) {
            if (!mPrefs.wake(Prefs.WAKE_SOS)) {
                return;
            }
            if (!mPrefs.on(Prefs.TORCH_DOUBLE_SOS)) {
                onSos();
            } else if (mPendingSos != null) {
                mHandler.removeCallbacks(mPendingSos);
                mPendingSos = null;
                toggleTorch(true);
            } else {
                mPendingSos = () -> {
                    mPendingSos = null;
                    onSos();
                };
                mWakeLock.acquire(DOUBLE_PRESS_MS + 1000);
                mHandler.postDelayed(mPendingSos, DOUBLE_PRESS_MS);
            }
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!mPrefs.wake(Prefs.WAKE_VOLUME)) {
                return;
            }
            if (mShown) {
                extend();
            } else {
                wake(Renderer.Page.CLOCK, mPrefs.timeoutMs());
            }
        }
    }

    /** SOS: разбудить, а если уже горит — следующая страница (на шаре — сначала ответ). */
    private void onSos() {
        if (!mShown) {
            wake(Renderer.Page.CLOCK, mPrefs.timeoutMs());
            return;
        }
        if (mState.page == Renderer.Page.CALL) {
            extend();
            return;
        }
        if (mState.page == Renderer.Page.EIGHTBALL && mState.eightBall == null) {
            String[] answers = mContext.getResources().getStringArray(R.array.eightball_answers);
            mState.eightBall = answers[mRandom.nextInt(answers.length)];
            show(mPrefs.timeoutMs());
            return;
        }
        wake(nextPage(mState.page), mPrefs.timeoutMs());
    }

    private Renderer.Page nextPage(Renderer.Page page) {
        List<Renderer.Page> pages = new ArrayList<>();
        pages.add(Renderer.Page.CLOCK);
        if (mPrefs.on(Prefs.PAGE_MUSIC) && mState.musicTitle != null) {
            pages.add(Renderer.Page.MUSIC);
        }
        if (mPrefs.on(Prefs.PAGE_NOTIFICATIONS)) {
            pages.add(Renderer.Page.NOTIFICATIONS);
        }
        if (mPrefs.on(Prefs.PAGE_PET)) {
            pages.add(Renderer.Page.PET);
        }
        if (mPrefs.on(Prefs.PAGE_EIGHTBALL)) {
            pages.add(Renderer.Page.EIGHTBALL);
        }
        int i = pages.indexOf(page);
        return pages.get((i + 1) % pages.size());
    }

    private void onPhoneState(Intent intent) {
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (number == null) {
                return;  // приходит дважды: без номера и с номером
            }
            mState.ringing = true;
            mState.inCall = false;
            mState.callerNumber = number;
            mState.caller = lookupContact(number);
            if (mPrefs.enabled() && isLidClosed()) {
                wake(Renderer.Page.CALL, 0);
            }
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            mState.ringing = false;
            mState.inCall = true;
            if (mShown || isLidClosed()) {
                wake(Renderer.Page.CALL, mPrefs.timeoutMs());
            }
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            boolean wasCall = mState.ringing || mState.inCall;
            mState.ringing = false;
            mState.inCall = false;
            mState.caller = null;
            mState.callerNumber = null;
            if (!wasCall) {
                return;
            }
            mHandler.postDelayed(() -> {  // пропущенный попадает в журнал не сразу
                updateMissedCalls();
                if (mPrefs.enabled() && isLidClosed()) {
                    wake(Renderer.Page.CLOCK, mPrefs.timeoutMs());
                } else {
                    hide();
                }
            }, 1000);
        }
    }

    /** Из NotifyListener. */
    void onNotifications(List<Renderer.Notice> notices, boolean posted) {
        mHandler.post(() -> {
            mState.notices = notices;
            if (posted && mPrefs.enabled() && mPrefs.wake(Prefs.WAKE_NOTIFICATIONS) && isLidClosed()
                    && !mState.ringing) {
                wake(mPrefs.on(Prefs.PAGE_NOTIFICATIONS) ? Renderer.Page.NOTIFICATIONS : Renderer.Page.CLOCK,
                        mPrefs.timeoutMs());
            } else {
                redraw();
            }
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        mHandler.post(() -> {
            if (Prefs.ENABLED.equals(key) && !mPrefs.enabled()) {
                hide();
            } else if (Prefs.STEPS.equals(key)) {
                initSteps();
            } else {
                redraw();
            }
        });
    }

    // ================================================================ показ

    /** Из настроек: показать сейчас (для проверки). */
    void showNow() {
        mHandler.post(() -> wake(Renderer.Page.CLOCK, mPrefs.timeoutMs()));
    }

    /** Снимок состояния для превью в настройках (вызывается из UI-потока). */
    Renderer.State snapshot() {
        return mState.copy();
    }

    /** Включить экран на странице {@code page}; {@code durationMs} = 0 — до явного hide(). */
    private void wake(Renderer.Page page, long durationMs) {
        if (!mPrefs.enabled()) {
            return;
        }
        if (mState.page != page) {
            mState.eightBall = null;
        }
        mState.page = page;
        if (mStepSensor != null) {
            mSensors.flush(mStepListener);
        }
        show(durationMs);
    }

    private void show(long durationMs) {
        mHandler.removeCallbacks(mOffRunnable);
        mWakeLock.acquire(durationMs > 0 ? durationMs + 2000 : 10 * 60 * 1000);
        mPanel.show(mRenderer.draw(mState), mPrefs.brightnessPercent());
        mShown = true;
        if (durationMs > 0) {
            mHandler.postDelayed(mOffRunnable, durationMs);
        }
        scheduleTick();
    }

    /** Продлить показ (кнопка нажата при горящем экране). */
    private void extend() {
        if (mShown && !mState.ringing) {
            show(mPrefs.timeoutMs());
        }
    }

    private void redraw() {
        if (mShown) {
            mPanel.show(mRenderer.draw(mState), mPrefs.brightnessPercent());
        }
    }

    private void hide() {
        mHandler.removeCallbacks(mOffRunnable);
        mHandler.removeCallbacks(mTick);
        if (mShown) {
            mPanel.off();
            mShown = false;
        }
        mState.page = Renderer.Page.CLOCK;
        mState.eightBall = null;
        mState.toast = null;
        mWakeLock.release();
    }

    /** Перерисовка: анимации — часто, секунды — раз в секунду, иначе — в начале минуты. */
    private void scheduleTick() {
        mHandler.removeCallbacks(mTick);
        if (!mShown) {
            return;
        }
        long delay;
        Renderer.Page p = mState.page;
        if (p == Renderer.Page.CHARGING) {
            delay = CHARGING_FRAME_MS;
        } else if (p == Renderer.Page.PET || (p == Renderer.Page.CALL && mState.ringing)) {
            delay = ANIM_FRAME_MS;
        } else if (p == Renderer.Page.CLOCK && mPrefs.seconds()) {
            delay = 1000 - System.currentTimeMillis() % 1000;
        } else {
            delay = 60_000 - System.currentTimeMillis() % 60_000;
        }
        mHandler.postDelayed(mTick, delay);
    }

    private void tick() {
        mState.frame++;
        redraw();
        scheduleTick();
    }

    private void toast(String text) {
        mState.toast = text;
        mHandler.removeCallbacks(mClearToast);
        mHandler.postDelayed(mClearToast, TOAST_MS);
    }

    // ================================================================ крышка

    private boolean isLidClosed() {
        Integer hall = readHall();
        if (hall == null) {
            return mLidClosedByEvent;
        }
        return hall == mPrefs.raw().getInt(Prefs.HALL_CLOSED, 0);
    }

    private static Integer readHall() {
        FileDescriptor fd = null;
        try {
            fd = Os.open(HALL_DEV, OsConstants.O_RDONLY, 0);
            return Os.ioctlInt(fd, HALL_IOC_GET);
        } catch (ErrnoException e) {
            Log.w(TAG, "hall: " + e.getMessage());
            return null;
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd);
                } catch (ErrnoException ignored) {
                }
            }
        }
    }

    // ================================================================ фонарик

    private void initTorch() {
        mCameras = mContext.getSystemService(CameraManager.class);
        try {
            for (String id : mCameras.getCameraIdList()) {
                Boolean flash = mCameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    mTorchCamera = id;
                    break;
                }
            }
            mCameras.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(mTorchCamera)) {
                        mTorchOn = enabled;
                    }
                }
            }, mHandler);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "torch init", e);
        }
    }

    /** Фонарик; {@code onSubScreen} — показать отметку на внешнем экране (кнопка SOS при закрытой крышке). */
    private void toggleTorch(boolean onSubScreen) {
        if (mTorchCamera == null) {
            return;
        }
        try {
            mTorchOn = !mTorchOn;
            mCameras.setTorchMode(mTorchCamera, mTorchOn);
            if (!onSubScreen) {
                return;
            }
            toast(mContext.getString(mTorchOn ? R.string.torch_on : R.string.torch_off));
            if (mShown) {
                show(mPrefs.timeoutMs());
            } else {
                wake(Renderer.Page.CLOCK, TOAST_MS + 500);
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "torch", e);
        }
    }

    // ================================================================ шаги

    private final SensorEventListener mStepListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            mStepsTotal = event.values[0];
            updateSteps();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private void initSteps() {
        if (mSensors == null) {
            mSensors = mContext.getSystemService(SensorManager.class);
        }
        if (mStepSensor != null) {
            mSensors.unregisterListener(mStepListener);
            mStepSensor = null;
        }
        if (!mPrefs.on(Prefs.STEPS)) {
            mState.steps = -1;
            return;
        }
        mStepSensor = mSensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (mStepSensor != null) {
            // Батчинг до 5 минут: датчик не будит процессор ради каждого шага.
            mSensors.registerListener(mStepListener, mStepSensor, SensorManager.SENSOR_DELAY_NORMAL,
                    5 * 60 * 1_000_000, mHandler);
        }
    }

    /** Счётчик датчика — с загрузки; шаги за сегодня = счётчик − база на начало дня. */
    private void updateSteps() {
        if (mStepsTotal < 0) {
            return;
        }
        Calendar c = Calendar.getInstance();
        int day = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
        SharedPreferences p = mPrefs.raw();
        float base = p.getFloat(Prefs.STEPS_BASE, 0);
        if (p.getInt(Prefs.STEPS_DAY, 0) != day) {
            base = mStepsTotal;
            p.edit().putInt(Prefs.STEPS_DAY, day).putFloat(Prefs.STEPS_BASE, base).apply();
        } else if (mStepsTotal < base) {  // перезагрузка: счётчик начался с нуля
            base = 0;
            p.edit().putFloat(Prefs.STEPS_BASE, 0).apply();
        }
        mState.steps = Math.round(mStepsTotal - base);
    }

    // ================================================================ музыка

    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateMedia();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMedia();
        }

        @Override
        public void onSessionDestroyed() {
            pickMediaSession();
        }
    };

    private void initMedia() {
        if (mMedia != null) {
            return;
        }
        try {
            mMedia = mContext.getSystemService(MediaSessionManager.class);
            mMedia.addOnActiveSessionsChangedListener(controllers -> pickMediaSession(), null, mHandler);
            pickMediaSession();
        } catch (RuntimeException e) {
            Log.w(TAG, "media init", e);
            mMedia = null;
        }
    }

    private void pickMediaSession() {
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mMediaCallback);
            mMediaController = null;
        }
        List<MediaController> sessions;
        try {
            sessions = mMedia == null ? null : mMedia.getActiveSessions(null);
        } catch (RuntimeException e) {
            sessions = null;
        }
        if (sessions != null && !sessions.isEmpty()) {
            MediaController best = sessions.get(0);
            for (MediaController c : sessions) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
            }
            mMediaController = best;
            mMediaController.registerCallback(mMediaCallback, mHandler);
        }
        updateMedia();
    }

    private void updateMedia() {
        MediaMetadata md = mMediaController == null ? null : mMediaController.getMetadata();
        PlaybackState st = mMediaController == null ? null : mMediaController.getPlaybackState();
        if (md == null) {
            mState.musicTitle = null;
            mState.musicArtist = null;
            mState.musicArt = null;
            mState.musicPlaying = false;
        } else {
            mState.musicTitle = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            mState.musicArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            mState.musicArt = art != null ? art : md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            mState.musicPlaying = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        }
        if (mState.page == Renderer.Page.MUSIC) {
            redraw();
        }
    }

    // ================================================================ данные

    private boolean unlocked() {
        return mContext.getSystemService(UserManager.class).isUserUnlocked();
    }

    private void updateMissedCalls() {
        if (!unlocked()) {
            return;
        }
        try (Cursor c = mContext.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[] {CallLog.Calls._ID},
                CallLog.Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE + " AND " + CallLog.Calls.NEW + "=1",
                null, null)) {
            mState.missedCalls = c == null ? 0 : c.getCount();
        } catch (RuntimeException e) {
            Log.w(TAG, "missed calls", e);
        }
    }

    private String lookupContact(String number) {
        if (TextUtils.isEmpty(number) || !unlocked()) {
            return number;
        }
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor c = mContext.getContentResolver().query(uri,
                new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "contact lookup", e);
        }
        return number;
    }

    /** Системному приложению доступ к уведомлениям выдаём сами (как делал сток). */
    private void enableNotificationListener() {
        if (!unlocked()) {
            return;
        }
        try {
            ComponentName listener = new ComponentName(mContext, NotifyListener.class);
            android.app.NotificationManager nm = mContext.getSystemService(android.app.NotificationManager.class);
            if (!nm.isNotificationListenerAccessGranted(listener)) {
                nm.setNotificationListenerAccessGranted(listener, true);
                Log.i(TAG, "notification listener enabled");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "enable notification listener", e);
        }
    }
}
