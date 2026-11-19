package com.jovx.api.catalog.storage;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** S3-compatible object storage (Cloudflare R2: region "auto", path-style addressing). */
public final class S3ImageStore implements ImageStore, AutoCloseable {
  private final S3Client client;
  private final String bucket;
  private final String publicBase;

  public S3ImageStore(String endpoint, String bucket, String accessKeyId, String secretAccessKey, String publicBase) {
    if (!endpoint.startsWith("https://") || !publicBase.startsWith("https://"))
      throw new IllegalArgumentException("Image storage URLs must use https");
    this.bucket = bucket;
    this.publicBase = publicBase.endsWith("/") ? publicBase : publicBase + "/";
    this.client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of("auto"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClient(UrlConnectionHttpClient.create())
        .build();
  }

  public boolean configured() { return true; }

  public void put(String key, byte[] bytes, String contentType) {
    client.putObject(b -> b.bucket(bucket).key(key).contentType(contentType)
        .cacheControl("public, max-age=31536000, immutable"), RequestBody.fromBytes(bytes));
  }

  public void delete(String key) {
    client.deleteObject(b -> b.bucket(bucket).key(key));
  }

  public String publicUrl(String key) {
    return publicBase + key;
  }

  @Override public void close() {
    client.close();
  }
}
