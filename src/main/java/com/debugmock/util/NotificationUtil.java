package com.debugmock.util;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Uniform notifications for the DebugMock notification group registered in plugin.xml.
 */
public final class NotificationUtil {

    private static final String GROUP = "DebugMock";

    private NotificationUtil() {
    }

    public static void info(@NotNull Project project, String title, String message) {
        notify(project, title, message, NotificationType.INFORMATION);
    }

    public static void warn(@NotNull Project project, String title, String message) {
        notify(project, title, message, NotificationType.WARNING);
    }

    private static void notify(@NotNull Project project, String title, String message, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP)
                .createNotification(title, message, type)
                .notify(project);
    }
}
