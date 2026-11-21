/**
 * Notifications: in-app inbox, push outbox with retries (Firebase Cloud Messaging), device tokens,
 * per-kind switches for SUPER_ADMIN and administration messages to partners (decisions 15, 29-31).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"common", "security::api", "partners::api"})
package com.jovx.api.notifications;
