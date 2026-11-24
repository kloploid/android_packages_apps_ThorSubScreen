package org.thor2.subscreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class SettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "SubScreenSettings";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_SHOW_NOW = "show_now";
    private static final String KEY_PICK_PHOTO = "pick_photo";

    private ActivityResultLauncher<String> mPickPhoto;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPickPhoto = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onPhotoPicked);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceManager pm = getPreferenceManager();
        pm.setStorageDeviceProtected();  // тот же файл, что читает SubScreenController
        pm.setSharedPreferencesName(Prefs.FILE);
        setPreferencesFromResource(R.xml.settings, rootKey);

        findPreference(KEY_SHOW_NOW).setOnPreferenceClickListener(p -> {
            SubScreenController.get(requireContext()).showNow();
            Toast.makeText(requireContext(), R.string.show_now_toast, Toast.LENGTH_SHORT).show();
            return true;
        });
        findPreference(KEY_PICK_PHOTO).setOnPreferenceClickListener(p -> {
            mPickPhoto.launch("image/*");
            return true;
        });
        SensorManager sensors = requireContext().getSystemService(SensorManager.class);
        if (sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) {
            findPreference(Prefs.STEPS).setVisible(false);
        }
        updateState();
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        refreshPreview();
    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        updateState();
        refreshPreview();
    }

    private void updateState() {
        ListPreference bg = findPreference(Prefs.BACKGROUND);
        Preference pick = findPreference(KEY_PICK_PHOTO);
        if (bg != null && pick != null) {
            pick.setVisible("photo".equals(bg.getValue()));
        }
    }

    private void refreshPreview() {
        PreviewPreference preview = findPreference(KEY_PREVIEW);
        if (preview != null) {
            preview.refresh();
        }
    }

    /** Фото → центрированная обрезка 284×240 → background.png в device-protected files. */
    private void onPhotoPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        Context context = requireContext().getApplicationContext();
        new Thread(() -> {
            try {
                Bitmap src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri),
                        (decoder, info, s) -> {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                            int sample = Math.max(1, Math.min(w / Renderer.W, h / Renderer.H) / 2);
                            decoder.setTargetSampleSize(sample);
                        });
                float scale = Math.max((float) Renderer.W / src.getWidth(), (float) Renderer.H / src.getHeight());
                int sw = Math.round(src.getWidth() * scale), sh = Math.round(src.getHeight() * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
                Bitmap crop = Bitmap.createBitmap(scaled, (sw - Renderer.W) / 2, (sh - Renderer.H) / 2,
                        Renderer.W, Renderer.H);
                File out = new File(Prefs.storage(context).getFilesDir(), Prefs.PHOTO_FILE);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    crop.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        ListPreference bg = findPreference(Prefs.BACKGROUND);
                        if (bg != null) {
                            bg.setValue("photo");  // вызовет перерисовку через слушатель
                        }
                        refreshPreview();
                    });
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "photo", e);
            }
        }).start();
    }
}
