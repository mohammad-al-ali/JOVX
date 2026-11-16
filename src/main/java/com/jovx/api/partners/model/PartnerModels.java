package com.jovx.api.partners.model;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class PartnerModels {
  private PartnerModels() {}

  public record Owner(UUID id, UUID accountId, String status, BigDecimal minimum) {}

  public record Branch(UUID id, String name) {}

  public record Establishment(UUID id, String name, List<Branch> branches) {}

  public record Reference(UUID id, String name) {}

  public record Address(UUID id, UUID cityId, String name) {}

  public record PartnerDiscount(BigDecimal minimum) {
    public PartnerDiscount {
      valid(minimum);
    }

    public void requireAllowed(BigDecimal productDiscount) {
      valid(productDiscount);
      if (productDiscount.compareTo(minimum) < 0)
        throw new IllegalArgumentException("DISCOUNT_BELOW_PARTNER_MINIMUM");
    }

    private static void valid(BigDecimal value) {
      if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0)
        throw new IllegalArgumentException("INVALID_DISCOUNT");
    }
  }
}
