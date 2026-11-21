package com.jovx.api.notifications.push;

import com.jovx.api.notifications.model.NotificationModels.PushResult;
import java.util.Map;

/** Port to the push provider. Without configuration the in-app inbox still works. */
public interface PushSender {
  boolean configured();

  PushResult send(String token, String title, String body, Map<String, String> data);

  /** Used when FIREBASE_SERVICE_ACCOUNT_BASE64 is not set (local development, tests). */
  PushSender NONE = new PushSender() {
    public boolean configured() {
      return false;
    }

    public PushResult send(String token, String title, String body, Map<String, String> data) {
      return PushResult.FAILED;
    }
  };
}
