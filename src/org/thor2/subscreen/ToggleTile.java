package org.thor2.subscreen;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Плитка быстрых настроек «Внешний экран»: вкл/выкл; долгое нажатие открывает настройки. */
public class ToggleTile extends TileService {
    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        Prefs prefs = new Prefs(this);
        prefs.raw().edit().putBoolean(Prefs.ENABLED, !prefs.enabled()).apply();
        update();
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(new Prefs(this).enabled() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
