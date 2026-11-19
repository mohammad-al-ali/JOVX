package com.jovx.api.partners.model;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class PartnerModels {
  private PartnerModels() {}

  public record Owner(UUID id, UUID accountId, String status, BigDecimal minimum) {}

  /** One row of the administration partner list, built by a single aggregate query. */
  public record OwnerListing(
      UUID id,
      UUID accountId,
      String status,
      BigDecimal minimum,
      List<String> establishmentNames,
      int branchCount) {}

  public record Branch(UUID id, String name) {}

  public record Establishment(UUID id, String name, List<Branch> branches) {}

  public record Reference(UUID id, String name) {}

  public record Address(UUID id, UUID cityId, String name) {}

  /** Default city and neighborhood preselected in forms and the public directory filter. */
  public record LocationDefaults(UUID cityId, String cityName, UUID addressId, String addressName) {}

  public record BranchDetail(
      UUID id,
      String name,
      UUID cityId,
      String cityName,
      UUID addressId,
      String addressName,
      String addressDetails,
      String status) {}

  public record EstablishmentDetail(
      UUID id,
      String name,
      UUID categoryId,
      String categoryName,
      String contactPhone,
      String status,
      List<BranchDetail> branches) {}

  public record EmployeeAssignment(UUID userId, Set<UUID> branchIds) {}

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

  public record PublicEstablishment(
      UUID id, String name, String category, java.math.BigDecimal discountPercent, String contactPhone, List<String> cities) {}
}
