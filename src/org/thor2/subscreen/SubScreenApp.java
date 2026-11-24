package org.thor2.subscreen;

import android.app.Application;

/** persistent-приложение: система запускает его при загрузке и держит живым. */
public class SubScreenApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SubScreenController.get(this).start();
    }
}
