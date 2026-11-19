package com.jovx.api.catalog.storage;

import java.util.Optional;

/**
 * Where product images live. Implementations: S3-compatible (Cloudflare R2 online), a local
 * directory (development) and "not configured" (uploads refused, the rest of the store works).
 */
public interface ImageStore {
  boolean configured();

  void put(String key, byte[] bytes, String contentType);

  void delete(String key);

  /** Absolute URL, or a path under this API for the local store. */
  String publicUrl(String key);

  /** Only the local store serves bytes through the API. */
  default Optional<byte[]> read(String key) {
    return Optional.empty();
  }

  final class NotConfigured implements ImageStore {
    public boolean configured() { return false; }
    public void put(String key, byte[] bytes, String contentType) { throw new IllegalStateException("Image storage not configured"); }
    public void delete(String key) { }
    public String publicUrl(String key) { return null; }
  }
}
