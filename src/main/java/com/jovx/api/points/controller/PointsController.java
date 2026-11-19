package com.jovx.api.points.controller;

import com.jovx.api.points.repository.PointsRepository.Entry;
import com.jovx.api.points.service.PointsQueryService;
import com.jovx.api.points.service.PointsQueryService.Balance;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** The member's own points; the session is re-checked by the JWT converter on every request. */
@RestController
@Profile("local-postgres")
@PreAuthorize("hasRole('MEMBER')")
public class PointsController {
  private final PointsQueryService points;

  public PointsController(PointsQueryService points) {
    this.points = points;
  }

  @GetMapping("/api/v1/member/points")
  public Balance balance(JwtAuthenticationToken a) {
    return points.balance(UUID.fromString(a.getName()));
  }

  @GetMapping("/api/v1/member/points/history")
  public List<Entry> history(
      JwtAuthenticationToken a,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit) {
    return points.history(UUID.fromString(a.getName()), offset, limit);
  }
}
