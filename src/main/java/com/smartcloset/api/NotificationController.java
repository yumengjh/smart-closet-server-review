package com.smartcloset.api;

import com.smartcloset.image.ProcessingException;
import com.smartcloset.notification.NotificationService;
import com.smartcloset.security.ClosetIdentity;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** In-site notifications for the processing centre (design 模型内存与图像向量 §8.2). */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit) {
        String owner = ClosetIdentity.username();
        return Map.of("items", notifications.list(owner, limit).stream().map(NotificationController::view).toList(), "unread", notifications.unread(owner));
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> read(@PathVariable UUID id) {
        String owner = ClosetIdentity.username();
        if (!notifications.read(owner, id.toString())) throw new ProcessingException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "没有找到这条通知。");
        return Map.of("unread", notifications.unread(owner));
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll() {
        String owner = ClosetIdentity.username();
        notifications.readAll(owner);
        return Map.of("unread", notifications.unread(owner));
    }

    static Map<String, Object> view(NotificationService.Notification n) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", n.id()); out.put("kind", n.kind()); out.put("title", n.title()); out.put("body", n.body()); out.put("href", n.href());
        out.put("payload", n.payload()); out.put("createdAt", n.createdAt()); out.put("read", n.readAt() != null);
        return out;
    }
}
