package com.jovx.api.partners.dto;

import com.jovx.api.partners.model.PartnerModels.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class PartnerDtos {
  private PartnerDtos() {}

  public record CreateEmployeeRequest(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String phone,
      @NotNull @Pattern(regexp = "[0-9]{6}") String pin,
      @NotEmpty Set<@NotNull UUID> branchIds,
      @NotNull @Size(max = 100) Set<@NotBlank String> permissions) {
    @Override
    public String toString() {
      return "EmployeeCommand[credentials redacted]";
    }
  }

  public record EmployeePermissionsRequest(
      @NotNull @Size(max = 100) Set<@NotBlank String> permissions) {}

  public record RegisterPartnerRequest(
      @NotBlank @Size(max = 200) String userName,
      @NotBlank @Size(max = 200) String name,
      @NotNull UUID categoryId,
      @NotBlank @Size(max = 64) String phone,
      @NotNull @Pattern(regexp = "[0-9]{6}") String pin,
      @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
          BigDecimal discountPercent,
      @NotBlank @Size(max = 200) String branchName,
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
      Set<UUID> branchIds,
      Set<String> permissions) {}

  public record PartnerContextResponse(
      UUID partnerId, BigDecimal minimumDiscountPercent, List<Establishment> establishments) {}

  public record PartnerCreatedResponse(
      UUID partnerId, UUID accountId, UUID establishmentId, UUID branchId, String status) {}

  public record ReferenceDataResponse(
      List<Reference> cities, List<Address> addresses, List<Reference> categories) {}

  public record ReferenceRequest(@NotBlank @Size(max = 200) String name) {}

  public record AddressRequest(@NotNull UUID cityId, @NotBlank @Size(max = 200) String name) {}

  public record PartnerSummary(
      UUID partnerId,
      UUID accountId,
      String ownerName,
      String phone,
      String status,
      BigDecimal minimumDiscountPercent,
      List<String> establishmentNames,
      int branchCount) {}

  public record PartnerDetail(
      UUID partnerId,
      UUID accountId,
      String ownerName,
      String phone,
      String status,
      BigDecimal minimumDiscountPercent,
      List<EstablishmentDetail> establishments) {}

  public record DiscountRequest(
      @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
          BigDecimal discountPercent) {}

  /** contactPhone is optional, independent of the login phone; null or blank clears it. */
  public record EstablishmentRequest(
      @NotBlank @Size(max = 200) String name,
      @NotNull UUID categoryId,
      @Size(max = 64) String contactPhone) {}

  public record NewEstablishmentRequest(
      @NotBlank @Size(max = 200) String name,
      @NotNull UUID categoryId,
      @Size(max = 64) String contactPhone,
      @NotBlank @Size(max = 200) String branchName,
      @NotNull UUID cityId,
      @NotNull UUID addressId,
      @Size(max = 500) String addressDetails) {}

  public record EstablishmentCreatedResponse(UUID establishmentId, UUID branchId, String status) {}

  public record BranchRequest(
      @NotBlank @Size(max = 200) String name,
      @NotNull UUID cityId,
      @NotNull UUID addressId,
      @Size(max = 500) String addressDetails) {}

  public record BranchCreatedResponse(UUID branchId, String status) {}

  public record StatusRequest(@NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}

  public record EmployeeView(
      UUID userId,
      String name,
      String phone,
      String status,
      Set<UUID> branchIds,
      Set<String> permissions) {}

  public record EmployeePinRequest(@NotNull @Pattern(regexp = "[0-9]{6}") String pin) {
    @Override
    public String toString() {
      return "EmployeePin[redacted]";
    }
  }

  public record EmployeeBranchesRequest(@NotEmpty @Size(max = 200) Set<@NotNull UUID> branchIds) {}
}
