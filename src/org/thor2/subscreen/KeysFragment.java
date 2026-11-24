package org.thor2.subscreen;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KeysFragment extends PreferenceFragmentCompat {
    private static final String KEY_CATEGORY = "cat_buttons";
    private static final String KEY_END_BUTTON = "end_button";
    private static final String KEY_T9 = "t9";
    private static final String T9_URL = "https://github.com/sspanak/tt9";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceManager pm = getPreferenceManager();
        pm.setStorageDeviceProtected();
        pm.setSharedPreferencesName(Prefs.FILE);
        setPreferencesFromResource(R.xml.keys, rootKey);
        Context c = requireContext();

        // назначаемые кнопки: короткое и долгое нажатие
        List<CharSequence> names = new ArrayList<>();
        List<CharSequence> values = new ArrayList<>();
        String[] builtinValues = {KeyActions.NONE, KeyActions.CONTACTS, KeyActions.MESSAGES, KeyActions.DIALER,
                KeyActions.CALL_LOG, KeyActions.CAMERA, KeyActions.TORCH, KeyActions.NOTIFICATIONS,
                KeyActions.QUICK_SETTINGS, KeyActions.CURSOR};
        String[] builtinNames = getResources().getStringArray(R.array.key_action_entries);
        for (int i = 0; i < builtinValues.length; i++) {
            values.add(builtinValues[i]);
            names.add(builtinNames[i]);
        }
        PackageManager packages = c.getPackageManager();
        List<ResolveInfo> apps = packages.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.loadLabel(packages).toString(), b.loadLabel(packages).toString()));
        for (ResolveInfo ri : apps) {
            values.add(KeyActions.APP_PREFIX + ri.activityInfo.packageName + "/" + ri.activityInfo.name);
            names.add(getString(R.string.key_action_app, ri.loadLabel(packages)));
        }
        CharSequence[] entryNames = names.toArray(new CharSequence[0]);
        CharSequence[] entryValues = values.toArray(new CharSequence[0]);

        PreferenceCategory cat = findPreference(KEY_CATEGORY);
        int order = 0;
        for (Map.Entry<Integer, String[]> e : KeyActions.DEFAULTS.entrySet()) {
            for (int press = 0; press < 2; press++) {
                ListPreference lp = new ListPreference(c);
                lp.setKey(KeyActions.prefKey(e.getKey(), press == 1));
                lp.setTitle(press == 0 ? keyName(e.getKey())
                        : getString(R.string.key_long_press, keyName(e.getKey())));
                lp.setDialogTitle(lp.getTitle());
                lp.setEntries(entryNames);
                lp.setEntryValues(entryValues);
                lp.setDefaultValue(e.getValue()[press]);
                lp.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                lp.setOrder(order++);
                lp.setIconSpaceReserved(false);
                cat.addPreference(lp);
            }
        }

        // долгое «Домой» — шторка: дублируем в Settings.Global, его читает PhoneWindowManager
        SwitchPreferenceCompat homeShade = findPreference(Prefs.HOME_SHADE);
        homeShade.setChecked(Settings.Global.getInt(c.getContentResolver(), "thor2_home_long_press_shade", 1) == 1);
        homeShade.setOnPreferenceChangeListener((p, v) -> Settings.Global.putInt(c.getContentResolver(),
                "thor2_home_long_press_shade", (Boolean) v ? 1 : 0));

        // красная кнопка: стандартная настройка Android END_BUTTON_BEHAVIOR
        ListPreference end = findPreference(KEY_END_BUTTON);
        end.setValue(String.valueOf(Settings.System.getInt(c.getContentResolver(),
                Settings.System.END_BUTTON_BEHAVIOR, Settings.System.END_BUTTON_BEHAVIOR_DEFAULT)));
        end.setOnPreferenceChangeListener((p, v) -> Settings.System.putInt(c.getContentResolver(),
                Settings.System.END_BUTTON_BEHAVIOR, Integer.parseInt((String) v)));

        Preference t9 = findPreference(KEY_T9);
        t9.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(T9_URL)));
            return true;
        });
    }

    private String keyName(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_F1: return getString(R.string.key_f1);
            case KeyEvent.KEYCODE_F2: return getString(R.string.key_f2);
            case KeyEvent.KEYCODE_F3: return getString(R.string.key_f3);
            case KeyEvent.KEYCODE_CAMERA: return getString(R.string.key_camera);
            case KeyEvent.KEYCODE_CALL: return getString(R.string.key_call);
            default: return KeyEvent.keyCodeToString(keyCode);
        }
    }
}
