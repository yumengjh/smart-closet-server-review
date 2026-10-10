package com.smartcloset.notification;

/**
 * Where a notification goes besides the in-site list (design 模型内存与图像向量 G13). Today there is none; e-mail later
 * is one more implementation plus a user switch, without touching the code that decides what to notify.
 */
public interface NotificationChannel {
    String name();
    /** Called after the in-site row is stored; failures are logged and never undo the in-site notification. */
    void deliver(NotificationService.Notification notification);
}
