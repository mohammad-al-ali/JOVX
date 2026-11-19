package com.jovx.api.points.dto;

import java.time.Instant;
import java.util.UUID;

/** HTTP contract of the points module; independent of how rows are stored. */
public final class PointsDtos {
  private PointsDtos() {}

  /** The full balance; frozen means visible but not spendable (membership term ended). */
  public record Balance(long balance, boolean frozen) {}

  public record HistoryEntry(
      UUID id, long delta, long balanceAfter, String kind, UUID referenceId, Instant createdAt) {}
}
