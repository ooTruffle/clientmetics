package com.ootruffle.clientmetics.compat;

import java.util.concurrent.Callable;
import org.polyfrost.oneconfig.api.notifications.v1.Notification;
import org.polyfrost.oneconfig.api.notifications.v1.NotificationAction;
import org.polyfrost.oneconfig.api.notifications.v1.NotificationType;
import org.polyfrost.oneconfig.api.notifications.v1.Notifications;

/**
 * The updater's OneConfig notifications (see {@link com.ootruffle.clientmetics.update.Updater}).
 * Only loaded through {@link OneConfigCompat} when OneConfig is installed. Notifications are
 * returned as plain objects so callers never touch OneConfig's classes.
 */
final class UpdateNotifications {

    private UpdateNotifications() {}

    /** A notification that stays until it's answered or closed, with a primary and a secondary button. */
    static Object ask(String title, String message, boolean success, String yes, Runnable onYes, String no, Runnable onNo) {
        return Notifications.builder(title, message)
                .type(success ? NotificationType.SUCCESS : NotificationType.INFO)
                .persistent()
                .action(new NotificationAction(yes, true, onYes))
                .action(new NotificationAction(no, false, onNo))
                .send();
    }

    /** A progress bar that stays until {@link #dismiss}. */
    static Object progress(String title, String message, Callable<Float> progress) {
        return Notifications.builder(title, message)
                .type(NotificationType.PROGRESS)
                .persistent()
                .progress(progress)
                .send();
    }

    static Object error(String title, String message) {
        return Notifications.error(title, message, 15_000L);
    }

    static void dismiss(Object notification) {
        if (notification instanceof Notification) {
            Notifications.dismiss((Notification) notification);
        }
    }

}
