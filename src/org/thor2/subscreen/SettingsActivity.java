package org.thor2.subscreen;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Настройки внешнего экрана (Настройки → Экран → Внешний экран, плитка QS — долгое нажатие). */
public class SettingsActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, new SettingsFragment())
                    .commit();
        }
    }
}
