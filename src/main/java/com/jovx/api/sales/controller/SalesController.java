package com.jovx.api.sales.controller;

import com.jovx.api.sales.dto.SalesDtos.*;
import com.jovx.api.sales.service.SaleService;
import com.jovx.api.sales.service.SalesReadService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Partner point of sale; the services re-check every grant and store inside the transaction. */
@RestController
@Profile("local-postgres")
public class SalesController {
  private static final String PARTNER_SIDE = "hasRole('PARTNER') or hasRole('PARTNER_EMPLOYEE')";
  private final SaleService sales;
  private final SalesReadService reads;

  public SalesController(SaleService sales, SalesReadService reads) {
    this.sales = sales; this.reads = reads;
  }

  @GetMapping("/api/v1/partner/sales/context")
  @PreAuthorize(PARTNER_SIDE)
  public SalesContext context(JwtAuthenticationToken a) {
    return sales.context(id(a), sid(a), role(a));
  }

  @GetMapping("/api/v1/partner/sales/stores/{storeId}/products")
  @PreAuthorize("hasRole('PARTNER') or hasAuthority('sale.confirm')")
  public List<SellableProductView> products(JwtAuthenticationToken a, @PathVariable UUID storeId) {
    return sales.sellable(id(a), sid(a), role(a), storeId);
  }

  @PostMapping("/api/v1/partner/sales/scans")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PARTNER') or hasAuthority('member.scan')")
  public ScanView scan(JwtAuthenticationToken a, @Valid @RequestBody ScanRequest body) {
    return sales.scan(id(a), sid(a), role(a), body);
  }

  @PostMapping("/api/v1/partner/sales")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PARTNER') or hasAuthority('sale.confirm') or hasAuthority('sale.free')")
  public SaleView sell(JwtAuthenticationToken a, @Valid @RequestBody SaleRequest body) {
    return sales.sell(id(a), sid(a), role(a), body);
  }

  @GetMapping("/api/v1/partner/sales")
  @PreAuthorize("hasRole('PARTNER') or hasAuthority('sale.read.own') or hasAuthority('sale.read.branch')")
  public List<SaleView> history(
      JwtAuthenticationToken a, @RequestParam(required = false) UUID storeId,
      @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
    return reads.history(id(a), sid(a), role(a), storeId, offset, limit);
  }

  @GetMapping("/api/v1/partner/sales/summary")
  @PreAuthorize("hasRole('PARTNER') or hasAuthority('report.read.branch') or hasAuthority('report.read.partner')")
  public SalesSummary summary(
      JwtAuthenticationToken a, @RequestParam(required = false) UUID storeId, @RequestParam(defaultValue = "1") int days) {
    return reads.summary(id(a), sid(a), role(a), storeId, days);
  }

  @GetMapping("/api/v1/partner/sales/{saleId}")
  @PreAuthorize("hasRole('PARTNER') or hasAuthority('sale.read.own') or hasAuthority('sale.read.branch')")
  public SaleView receipt(JwtAuthenticationToken a, @PathVariable UUID saleId) {
    return reads.receipt(id(a), sid(a), role(a), saleId);
  }

  @GetMapping("/api/v1/member/purchases")
  @PreAuthorize("hasRole('MEMBER')")
  public MemberPurchases purchases(
      JwtAuthenticationToken a, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
    return reads.memberPurchases(id(a), sid(a), offset, limit);
  }

  private static UUID id(JwtAuthenticationToken a) {
    return UUID.fromString(a.getName());
  }

  private static UUID sid(JwtAuthenticationToken a) {
    return UUID.fromString(a.getToken().getClaimAsString("sid"));
  }

  private static String role(JwtAuthenticationToken a) {
    return a.getToken().getClaimAsString("role");
  }
}
