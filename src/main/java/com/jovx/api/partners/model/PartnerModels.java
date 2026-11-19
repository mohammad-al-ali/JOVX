package com.jovx.api.partners.model;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;

/**
 * Partner domain (decision 2026-11-19): a partner owns one or more stores. A store is one place
 * with its own name, category, location, optional contact, map point, description, logo and
 * weekly hours. Products (later phase) belong to exactly one store.
 */
public final class PartnerModels {
  private PartnerModels() {}

  public record Owner(UUID id, UUID accountId, String status, BigDecimal minimum) {}

  /** One row of the administration partner list, built by a single aggregate query. */
  public record OwnerListing(
      UUID id,
      UUID accountId,
      String status,
      BigDecimal minimum,
      List<String> storeNames,
      int storeCount) {}

  /** An active store as offered to the owner when assigning employees. */
  public record StoreRef(UUID id, String name) {}

  public record Reference(UUID id, String name) {}

  public record Address(UUID id, UUID cityId, String name) {}

  /** Default city and neighborhood preselected in forms and the public directory filter. */
  public record LocationDefaults(UUID cityId, String cityName, UUID addressId, String addressName) {}

  /** What a store says about itself; validated by {@link #validate()} before any write. */
  public record StoreData(
      String name,
      UUID categoryId,
      UUID cityId,
      UUID addressId,
      String addressDetails,
      String contactPhone,
      BigDecimal latitude,
      BigDecimal longitude,
      String description) {
    /** @return the failure code, or null when the data is acceptable. */
    public String validate() {
      if (name == null || name.isBlank()) return "INVALID_STORE_NAME";
      if ((latitude == null) != (longitude == null)) return "INVALID_MAP_POINT";
      if (latitude != null
          && (latitude.abs().compareTo(new BigDecimal("90")) > 0
              || longitude.abs().compareTo(new BigDecimal("180")) > 0)) return "INVALID_MAP_POINT";
      return null;
    }
  }

  /**
   * Opening hours of one weekday (ISO: 1 = Monday ... 7 = Sunday), Damascus local time.
   * A closing time earlier than the opening time means the store closes after midnight.
   */
  public record StoreHours(int weekday, boolean closed, LocalTime opensAt, LocalTime closesAt) {
    /** @return the failure code for a full week, or null when all seven days are valid. */
    public static String validateWeek(List<StoreHours> week) {
      if (week == null || week.size() != 7) return "INVALID_STORE_HOURS";
      var seen = new HashSet<Integer>();
      for (var day : week) {
        if (day.weekday() < 1 || day.weekday() > 7 || !seen.add(day.weekday()))
          return "INVALID_STORE_HOURS";
        if (day.closed() ? (day.opensAt() != null || day.closesAt() != null)
            : (day.opensAt() == null || day.closesAt() == null || day.opensAt().equals(day.closesAt())))
          return "INVALID_STORE_HOURS";
      }
      return null;
    }
  }

  public record StoreDetail(
      UUID id,
      String name,
      UUID categoryId,
      String categoryName,
      UUID cityId,
      String cityName,
      UUID addressId,
      String addressName,
      String addressDetails,
      String contactPhone,
      BigDecimal latitude,
      BigDecimal longitude,
      String description,
      String logoKey,
      String status,
      List<StoreHours> hours) {}

  public record EmployeeAssignment(UUID userId, Set<UUID> storeIds) {}

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

  /** Guest directory entry: one active store of an active partner. No owner or login data. */
  public record PublicStore(
      UUID id,
      String name,
      String category,
      BigDecimal discountPercent,
      String contactPhone,
      UUID cityId,
      String cityName,
      String addressName,
      String addressDetails,
      BigDecimal latitude,
      BigDecimal longitude,
      String description,
      List<StoreHours> hours) {}
}
