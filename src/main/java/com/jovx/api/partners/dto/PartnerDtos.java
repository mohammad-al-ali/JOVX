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
}
