package com.jovx.api.notifications.model;

import static org.junit.jupiter.api.Assertions.*;

import com.jovx.api.notifications.model.NotificationModels.Rules;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationRulesTest {
  @Test
  void retriesBackOffFromThirtySecondsAndDouble() {
    assertEquals(Duration.ofSeconds(30), Rules.backoff(1));
    assertEquals(Duration.ofMinutes(1), Rules.backoff(2));
    assertEquals(Duration.ofMinutes(8), Rules.backoff(5));
  }

  @Test
  void textIsTrimmedAndCutToTheColumn() {
    assertEquals("مرحبا", Rules.clip("  مرحبا ", 10));
    assertEquals(10, Rules.clip("x".repeat(50), 10).length());
    assertEquals("", Rules.clip(null, 10));
  }
}
