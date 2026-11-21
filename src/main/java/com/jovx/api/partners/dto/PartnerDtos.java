package com.jovx.api.partners.dto;

import com.jovx.api.partners.model.PartnerModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class PartnerDtos {
  private PartnerDtos() {}

  public record CreateEmployeeRequest(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String phone,
      @NotNull @Pattern(regexp = "[0-9]{6}") String pin,
      @NotEmpty @Size(max = 200) Set<@NotNull UUID> storeIds,
      @NotNull @Size(max = 100) Set<@NotBlank String> permissions) {
    @Override
    public String toString() {
      return "EmployeeCommand[credentials redacted]";
    }
  }

  public record EmployeePermissionsRequest(
      @NotNull @Size(max = 100) Set<@NotBlank String> permissions) {}

  /** The partner's first store is created with the account; both stay PENDING until activation. */
  public record RegisterPartnerRequest(
      @NotBlank @Size(max = 200) String userName,
      @NotBlank @Size(max = 200) String storeName,
      @NotNull UUID categoryId,
      @NotBlank @Size(max = 64) String phone,
      @NotNull @Pattern(regexp = "[0-9]{6}") String pin,
      @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
          BigDecimal discountPercent,
      @NotNull UUID cityId,
      @NotNull UUID addressId,
      @Size(max = 500) String addressDetails,
      @Size(max = 64) String contactPhone) {
    @Override
    public String toString() {
      return "PartnerRegistration.Command[credentials redacted]";
    }
  }

  public record EmployeeCreatedResponse(
      UUID userId,
      String role,
      String status,
      String relatedEntity,
      UUID relatedId,
      Set<UUID> storeIds,
      Set<String> permissions) {}

  /** Active stores only (for employee assignment); empty when every store is suspended. */
  public record PartnerContextResponse(
      UUID partnerId, BigDecimal minimumDiscountPercent, List<StoreRef> stores) {}

  /** owner=true holds every partner permission; an employee gets its explicit grants only. */
  public record WorkspaceResponse(
      UUID partnerId,
      boolean owner,
      BigDecimal minimumDiscountPercent,
      List<StoreRef> stores,
      Set<String> permissions) {}

  public record PartnerCreatedResponse(UUID partnerId, UUID accountId, UUID storeId, String status) {}

  /** defaults is null until administration chooses a default city and neighborhood. */
  public record ReferenceDataResponse(
      List<Reference> cities,
      List<Address> addresses,
      List<Reference> categories,
      LocationDefaultsView defaults) {}

  public record LocationDefaultsRequest(@NotNull UUID cityId, @NotNull UUID addressId) {}

  public record LocationDefaultsView(
      UUID cityId, String cityName, UUID addressId, String addressName) {
    public static LocationDefaultsView of(LocationDefaults d) {
      return new LocationDefaultsView(d.cityId(), d.cityName(), d.addressId(), d.addressName());
    }
  }

  public record ReferenceRequest(@NotBlank @Size(max = 200) String name) {}

  public record AddressRequest(@NotNull UUID cityId, @NotBlank @Size(max = 200) String name) {}

  public record PartnerSummary(
      UUID partnerId,
      UUID accountId,
      String ownerName,
      String phone,
      String status,
      BigDecimal minimumDiscountPercent,
      List<String> storeNames,
      int storeCount) {}

  public record PartnerDetail(
      UUID partnerId,
      UUID accountId,
      String ownerName,
      String phone,
      String status,
      BigDecimal minimumDiscountPercent,
      List<StoreDetail> stores) {}

  public record DiscountRequest(
      @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
          BigDecimal discountPercent) {}

  /**
   * Create or edit a store. Optional fields: null or blank clears them. contactPhone is independent
   * of the login phone. latitude and longitude are given together or not at all.
   */
  public record StoreRequest(
      @NotBlank @Size(max = 200) String name,
      @NotNull UUID categoryId,
      @NotNull UUID cityId,
      @NotNull UUID addressId,
      @Size(max = 500) String addressDetails,
      @Size(max = 64) String contactPhone,
      @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 6) BigDecimal latitude,
      @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude,
      @Size(max = 1000) String description) {
    public StoreData data() {
      return new StoreData(
          name, categoryId, cityId, addressId, addressDetails, contactPhone, latitude, longitude,
          description);
    }
  }

  public record StoreCreatedResponse(UUID storeId, String status) {}

  /** One weekday; times are "HH:mm" and omitted when closed. Close before open = after midnight. */
  public record DayHours(
      @NotNull @Min(1) @Max(7) Integer weekday,
      boolean closed,
      @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String opensAt,
      @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String closesAt) {}

  /** The whole week, exactly seven days, replaces the previous hours. */
  public record StoreHoursRequest(@NotNull @Size(min = 7, max = 7) List<@NotNull @Valid DayHours> days) {}

  public record StatusRequest(@NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}

  public record EmployeeView(
      UUID userId,
      String name,
      String phone,
      String status,
      Set<UUID> storeIds,
      Set<String> permissions) {}

  public record EmployeePinRequest(@NotNull @Pattern(regexp = "[0-9]{6}") String pin) {
    @Override
    public String toString() {
      return "EmployeePin[redacted]";
    }
  }

  public record EmployeeStoresRequest(@NotEmpty @Size(max = 200) Set<@NotNull UUID> storeIds) {}
}
