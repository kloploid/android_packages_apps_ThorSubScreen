package org.thor2.subscreen;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/** Собирает «настоящие» уведомления (без постоянных и сводок групп) для внешнего экрана. */
public class NotifyListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        report(null);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        report(counts(sbn) && !isUpdate(sbn) ? sbn : null);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        report(null);
    }

    private static boolean counts(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        return sbn.isClearable() && !sbn.isOngoing()
                && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0
                && !sbn.getPackageName().equals("org.thor2.subscreen");
    }

    /** Обновление уже показанного (прогресс, правка) не должно будить экран. */
    private static boolean isUpdate(StatusBarNotification sbn) {
        return (sbn.getNotification().flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
    }

    private void report(StatusBarNotification posted) {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException e) {
            return;  // слушатель ещё не подключён
        }
        List<StatusBarNotification> list = new ArrayList<>();
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                if (counts(sbn)) {
                    list.add(sbn);
                }
            }
        }
        list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        List<Renderer.Notice> notices = new ArrayList<>();
        for (StatusBarNotification sbn : list) {
            notices.add(toNotice(sbn));
        }
        SubScreenController.get(this).onNotifications(notices, posted != null);
    }

    private Renderer.Notice toNotice(StatusBarNotification sbn) {
        Renderer.Notice n = new Renderer.Notice();
        Bundle extras = sbn.getNotification().extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        n.title = title == null ? null : title.toString();
        n.text = text == null ? null : text.toString().replace('\n', ' ');
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(sbn.getPackageName(), 0);
            n.app = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            n.app = sbn.getPackageName();
        }
        try {
            Drawable icon = sbn.getNotification().getSmallIcon() == null ? null
                    : sbn.getNotification().getSmallIcon().loadDrawable(this);
            n.icon = icon == null ? null : icon.mutate();
        } catch (RuntimeException e) {
            n.icon = null;
        }
        return n;
    }
}
