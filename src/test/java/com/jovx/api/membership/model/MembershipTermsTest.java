package com.jovx.api.membership.model;

import static org.junit.jupiter.api.Assertions.*;

import com.jovx.api.membership.model.MembershipModels.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MembershipTermsTest {
  @Test
  void termsUseDamascusCalendarMonths() {
    // 2026-01-31 10:00 Damascus (+03:00) plus one month is the end of February.
    var start = Instant.parse("2026-01-31T07:00:00Z");
    assertEquals(Instant.parse("2026-02-28T07:00:00Z"), Terms.end(start, 1));
    assertEquals(Instant.parse("2026-07-31T07:00:00Z"), Terms.end(start, 6));
    assertThrows(IllegalArgumentException.class, () -> Terms.end(start, 0));
  }

  @Test
  void renewalKeepsRemainingDaysOrStartsNowWhenExpired() {
    var now = Instant.parse("2026-11-18T09:00:00Z");
    var later = Instant.parse("2026-12-01T09:00:00Z");
    var earlier = Instant.parse("2026-11-01T09:00:00Z");
    assertEquals(later, Terms.renewalStart(now, later));
    assertEquals(now, Terms.renewalStart(now, earlier));
    assertEquals(now, Terms.renewalStart(now, now));
  }

  @Test
  void stateEndsExactlyAtExpiry() {
    var expiry = Instant.parse("2026-12-01T09:00:00Z");
    assertEquals(MembershipState.ACTIVE, Terms.state(expiry.minusSeconds(1), expiry));
    assertEquals(MembershipState.EXPIRED, Terms.state(expiry, expiry));
  }

  @Test
  void memberNumbersAndCardCodes() {
    assertEquals("JVX-000042", Terms.memberNumber(42));
    assertEquals("JVX-1234567", Terms.memberNumber(1_234_567));
    assertEquals("JVXC-ABCD1234", Terms.cardCode("  JVXC-ABCD1234 "));
    assertEquals("KpdvPmdCaP+QxJrw==", Terms.cardCode(" KpdvPmdCaP+QxJrw== "), "Base64 from the printed PDF417");
    assertEquals("ab/cd+ef==", Terms.cardCode("ab/cd+ef=="));
    assertNull(Terms.cardCode("bad code"));
    assertNull(Terms.cardCode("abc"));
    assertNull(Terms.cardCode(null));
    assertEquals("•••• 1234", Terms.maskedCode("JVXC-ABCD1234"));
  }
}
