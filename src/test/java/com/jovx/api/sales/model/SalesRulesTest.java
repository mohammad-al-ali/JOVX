package com.jovx.api.sales.model;

import static org.junit.jupiter.api.Assertions.*;

import com.jovx.api.sales.model.SalesModels.Rules;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SalesRulesTest {
  @Test void freeSaleExampleOfDecisionSix() {
    assertEquals(10000, Rules.discount(100000, new BigDecimal("10")));
    assertEquals(2000, Rules.minimumPoints(100000));
  }

  @Test void pointsAndDiscountRoundToTheNearestWholeUnit() {
    assertEquals(247, Rules.minimumPoints(12345), "decision 12: 246.9 → 247");
    assertEquals(1852, Rules.discount(12345, new BigDecimal("15")), "1,851.75 → 1,852");
    assertEquals(0, Rules.minimumPoints(24), "0.48 → 0");
    assertEquals(1, Rules.minimumPoints(25), "0.5 rounds up");
  }

  @Test void discountMayBeRaisedButNotLoweredOrPastAHundred() {
    var minimum = new BigDecimal("10.00");
    assertTrue(Rules.validDiscount(new BigDecimal("10"), minimum));
    assertTrue(Rules.validDiscount(new BigDecimal("12.5"), minimum));
    assertFalse(Rules.validDiscount(new BigDecimal("9.99"), minimum));
    assertFalse(Rules.validDiscount(new BigDecimal("100.01"), minimum));
    assertFalse(Rules.validDiscount(new BigDecimal("10.001"), minimum), "two decimals at most");
  }

  @Test void todayStartsAtMidnightDamascusTime() {
    // 2026-11-20 22:30 UTC is already 2026-11-21 01:30 in Damascus (UTC+3).
    var now = Instant.parse("2026-11-20T22:30:00Z");
    assertEquals(Instant.parse("2026-11-20T21:00:00Z"), Rules.since(now, 1));
    assertEquals(Instant.parse("2026-11-14T21:00:00Z"), Rules.since(now, 7));
  }
}
