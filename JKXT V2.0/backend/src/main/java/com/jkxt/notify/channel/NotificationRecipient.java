package com.jkxt.notify.channel;

import java.util.UUID;

/** A target for a notification across channels (an invigilating teacher). */
public record NotificationRecipient(UUID teacherId, String name, String email, String phone) {
}
