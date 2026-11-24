package org.thor2.subscreen;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.nio.ByteBuffer;

import vendor.hnlens.hardware.presentation.ILsDisplayRegion;
import vendor.hnlens.hardware.presentation.ILsLCDPanelInfo;
import vendor.hnlens.hardware.presentation.ILsPresentation;

/**
 * Внешний экран через vendor-HAL ILsPresentation (панель GC9307C на SPI).
 *
 * Пользователь видит альбомную картинку {@link #WIDTH}×{@link #HEIGHT}; панель принимает её
 * повёрнутой на 270° (240×284) в RGB565 — так же делал стоковый LsPresentationService.
 * Все методы вызываются из одного потока (см. {@link SubScreenController}).
 */
final class Panel {
    private static final String TAG = "SubScreenPanel";
    private static final String HAL = "vendor.hnlens.hardware.presentation.ILsPresentation/default";
    /** getDisplayStatus(): панель закрыта, перед выводом нужен open(). */
    private static final int STATUS_CLOSED = 1;

    static final int WIDTH = 284;
    static final int HEIGHT = 240;

    /** Если панель не сообщила максимум подсветки (в стоке по умолчанию было 80). */
    private static final int DEFAULT_MAX_BRIGHTNESS = 100;

    private ILsPresentation mHal;
    private int mMaxBrightness = DEFAULT_MAX_BRIGHTNESS;
    private int mLevel = -1;
    private boolean mOn;

    private ILsPresentation hal() {
        if (mHal != null && mHal.asBinder().isBinderAlive()) {
            return mHal;
        }
        mHal = null;
        if (!ServiceManager.isDeclared(HAL)) {
            Log.e(TAG, HAL + " не объявлен в VINTF");
            return null;
        }
        IBinder binder = ServiceManager.waitForDeclaredService(HAL);
        if (binder == null) {
            Log.e(TAG, "нет сервиса " + HAL);
            return null;
        }
        mHal = ILsPresentation.Stub.asInterface(Binder.allowBlocking(binder));
        try {
            ILsLCDPanelInfo info = mHal.getPanleInfo();
            if (info != null) {
                if (info.max_bl_brightnees > 0) {
                    mMaxBrightness = info.max_bl_brightnees;
                }
                Log.i(TAG, "панель " + info.lcd_name + " " + info.pixel_width + "x"
                        + info.pixel_height + " bpp=" + info.lcd_bpp + " max_bl=" + info.max_bl_brightnees
                        + " levels=" + info.lcd_bl_level_nums);
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "getPanleInfo", e);
        }
        return mHal;
    }

    /** Подсветка в процентах (1..100) → уровень панели. */
    private int level(int percent) {
        return Math.max(1, Math.min(mMaxBrightness, Math.round(mMaxBrightness * percent / 100f)));
    }

    /** Показать кадр (альбомный WIDTH×HEIGHT) с яркостью {@code brightnessPercent}. */
    void show(Bitmap frame, int brightnessPercent) {
        ILsPresentation hal = hal();
        if (hal == null) {
            return;
        }
        try {
            if (hal.getDisplayStatus() == STATUS_CLOSED) {
                hal.open();
            }
            hal.displayRegion(toRegion(frame));
            int level = level(brightnessPercent);
            if (!mOn || level != mLevel) {
                hal.setBrightness(level);
                mLevel = level;
            }
            mOn = true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "show", e);
            mHal = null;
        }
    }

    /** Погасить подсветку и закрыть панель. */
    void off() {
        if (mOn) {
            forceOff();
        }
    }

    /** Погасить, даже если мы панель не включали (картинка загрузчика после старта). */
    void forceOff() {
        mOn = false;
        mLevel = -1;
        ILsPresentation hal = hal();
        if (hal == null) {
            return;
        }
        try {
            hal.setBrightness(0);
            if (hal.getDisplayStatus() != STATUS_CLOSED) {
                hal.close();
            }
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "off", e);
            mHal = null;
        }
    }

    private static ILsDisplayRegion toRegion(Bitmap frame) {
        Matrix rotate = new Matrix();
        rotate.postRotate(270);
        Bitmap rotated = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), rotate, true);
        Bitmap rgb565 = rotated.copy(Bitmap.Config.RGB_565, false);
        ByteBuffer buf = ByteBuffer.allocate(rgb565.getByteCount());
        rgb565.copyPixelsToBuffer(buf);

        ILsDisplayRegion region = new ILsDisplayRegion();
        region.displayBuffer = buf.array();
        region.bufferSize = region.displayBuffer.length;
        region.xStart = 0;
        region.xEnd = rgb565.getWidth() - 1;
        region.yStart = 0;
        region.yEnd = rgb565.getHeight() - 1;
        rotated.recycle();
        rgb565.recycle();
        return region;
    }
}
