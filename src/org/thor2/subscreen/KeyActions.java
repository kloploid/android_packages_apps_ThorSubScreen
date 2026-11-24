package org.thor2.subscreen;

import android.app.KeyguardManager;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.CallLog;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.WindowManagerGlobal;

import com.android.internal.policy.IKeyguardDismissCallback;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Кнопки раскладушки, которые приложение не обработало (их присылает PhoneWindowManager, патч
 * frameworks_base 0002): Контакты (F1), Почта (F2), F3, Камера, зелёная — короткое и долгое
 * нажатие по настройкам; крышка — ответ и сброс вызова, только если включено в настройках
 * (как LS_START_APP_BY_KEY и LS_HALL_AUTO_ANSWER/END_PHONE в стоке).
 */
final class KeyActions {
    private static final String TAG = "SubScreenKeys";

    static final int SOURCE_SCREEN_OFF = 0;
    static final int SOURCE_UNHANDLED = 1;
    static final int SOURCE_LID = 2;

    static final String NONE = "none";
    static final String CONTACTS = "contacts";
    static final String MESSAGES = "messages";
    static final String DIALER = "dialer";
    static final String CALL_LOG = "call_log";
    static final String CAMERA = "camera";
    static final String TORCH = "torch";
    static final String NOTIFICATIONS = "notifications";
    static final String QUICK_SETTINGS = "quick_settings";
    static final String CURSOR = "cursor";
    static final String APP_PREFIX = "app:";

    /** Назначаемые кнопки → {короткое, долгое} по умолчанию. */
    static final Map<Integer, String[]> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put(KeyEvent.KEYCODE_F1, new String[] {CONTACTS, NONE});
        DEFAULTS.put(KeyEvent.KEYCODE_F2, new String[] {MESSAGES, NONE});
        DEFAULTS.put(KeyEvent.KEYCODE_F3, new String[] {NOTIFICATIONS, CURSOR});
        DEFAULTS.put(KeyEvent.KEYCODE_CAMERA, new String[] {CAMERA, TORCH});
        DEFAULTS.put(KeyEvent.KEYCODE_CALL, new String[] {CALL_LOG, DIALER});
    }

    static String prefKey(int keyCode, boolean longPress) {
        return "key_" + keyCode + (longPress ? "_long" : "_short");
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Prefs mPrefs;
    private final Runnable mTorch;
    private Runnable mPendingLong;
    private boolean mLongFired;

    KeyActions(Context context, Handler handler, Prefs prefs, Runnable torch) {
        mContext = context;
        mHandler = handler;
        mPrefs = prefs;
        mTorch = torch;
    }

    String action(int keyCode, boolean longPress) {
        String[] def = DEFAULTS.get(keyCode);
        if (def == null) {
            return NONE;
        }
        return mPrefs.raw().getString(prefKey(keyCode, longPress), def[longPress ? 1 : 0]);
    }

    // ================================================================ необработанные клавиши

    void onUnhandled(int keyCode, int action) {
        Log.d(TAG, "unhandled " + KeyEvent.keyCodeToString(keyCode) + " action=" + action);
        if (action == KeyEvent.ACTION_DOWN) {
            cancelLong();
            mLongFired = false;
            String longAction = action(keyCode, true);
            if (!NONE.equals(longAction)) {
                mPendingLong = () -> {
                    mPendingLong = null;
                    mLongFired = true;
                    run(longAction);
                };
                mHandler.postDelayed(mPendingLong, ViewConfiguration.getLongPressTimeout());
            }
        } else if (action == KeyEvent.ACTION_UP) {
            cancelLong();
            if (!mLongFired) {
                run(action(keyCode, false));
            }
            mLongFired = false;
        }
    }

    private void cancelLong() {
        if (mPendingLong != null) {
            mHandler.removeCallbacks(mPendingLong);
            mPendingLong = null;
        }
    }

    // ================================================================ действия

    void run(String action) {
        Log.d(TAG, "run " + action);
        switch (action) {
            case NONE:
                return;
            case CONTACTS:
                start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS), true);
                return;
            case MESSAGES:
                String sms = Telephony.Sms.getDefaultSmsPackage(mContext);
                Intent launch = sms == null ? null : mContext.getPackageManager().getLaunchIntentForPackage(sms);
                start(launch != null ? launch
                        : Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING), true);
                return;
            case DIALER:
                start(new Intent(Intent.ACTION_DIAL), true);
                return;
            case CALL_LOG:
                start(new Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE), true);
                return;
            case CAMERA:
                boolean locked = mContext.getSystemService(KeyguardManager.class).isKeyguardLocked();
                start(new Intent(locked ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                        : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), false);
                return;
            case TORCH:
                mTorch.run();
                return;
            case NOTIFICATIONS:
                mContext.getSystemService(StatusBarManager.class).expandNotificationsPanel();
                return;
            case QUICK_SETTINGS:
                mContext.getSystemService(StatusBarManager.class).expandSettingsPanel();
                return;
            case CURSOR:
                CursorService.toggle(mContext);
                return;
            default:
                if (action.startsWith(APP_PREFIX)) {
                    ComponentName cn = ComponentName.unflattenFromString(action.substring(APP_PREFIX.length()));
                    if (cn != null) {
                        start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn),
                                true);
                    }
                }
        }
    }

    /** Запустить; на заблокированном экране сначала снять блокировку (при пароле — после ввода). */
    private void start(Intent intent, boolean dismissKeyguard) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Runnable launch = () -> {
            try {
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "no activity for " + intent);
            }
        };
        if (!dismissKeyguard || !mContext.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            launch.run();
            return;
        }
        try {
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(new IKeyguardDismissCallback.Stub() {
                @Override
                public void onDismissError() {
                }

                @Override
                public void onDismissSucceeded() {
                    mHandler.post(launch);
                }

                @Override
                public void onDismissCancelled() {
                }
            }, null);
        } catch (RemoteException e) {
            Log.w(TAG, "dismissKeyguard", e);
        }
    }

    // ================================================================ крышка и вызовы

    void onLid(int keyCode) {
        TelecomManager telecom = mContext.getSystemService(TelecomManager.class);
        if (telecom == null) {
            return;
        }
        try {
            if (keyCode == KeyEvent.KEYCODE_WAKEUP) {  // открыли
                if (mPrefs.on(Prefs.LID_ANSWER) && telecom.isRinging()) {
                    Log.i(TAG, "lid opened while ringing: answer");
                    telecom.acceptRingingCall();
                }
            } else if (keyCode == KeyEvent.KEYCODE_SLEEP) {  // закрыли
                if (mPrefs.on(Prefs.LID_END) && (telecom.isRinging() || telecom.isInCall())
                        && !headsetConnected()) {
                    Log.i(TAG, "lid closed during call: end");
                    telecom.endCall();
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "telecom", e);
        }
    }

    /** С гарнитурой разговор при закрытой крышке продолжается (как в стоке). */
    private boolean headsetConnected() {
        AudioManager audio = mContext.getSystemService(AudioManager.class);
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                return true;
            }
        }
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        return bt != null && bt.getProfileConnectionState(BluetoothProfile.HEADSET)
                == BluetoothProfile.STATE_CONNECTED;
    }
}
