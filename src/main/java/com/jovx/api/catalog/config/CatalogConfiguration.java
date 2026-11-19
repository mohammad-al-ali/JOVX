package com.jovx.api.catalog.config;

import com.jovx.api.catalog.storage.*;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("local-postgres")
public class CatalogConfiguration {
  @Bean("catalogTransactions")
  TransactionTemplate transactions(DataSource source) {
    return new TransactionTemplate(new JdbcTransactionManager(source));
  }

  @Bean
  ImageProcessor imageProcessor() {
    return new ImageProcessor();
  }

  /**
   * S3 (Cloudflare R2) when all STORAGE_S3_* values are present; otherwise a local directory when
   * STORAGE_LOCAL_DIR is set (development only); otherwise uploads are refused with
   * IMAGE_STORAGE_NOT_CONFIGURED and the rest of the catalog keeps working.
   */
  @Bean
  @ConditionalOnMissingBean(ImageStore.class)
  ImageStore imageStore(
      @Value("${jovx.storage.s3.endpoint:}") String endpoint,
      @Value("${jovx.storage.s3.bucket:}") String bucket,
      @Value("${jovx.storage.s3.access-key-id:}") String accessKey,
      @Value("${jovx.storage.s3.secret-access-key:}") String secret,
      @Value("${jovx.storage.public-base-url:}") String publicBase,
      @Value("${jovx.storage.local-dir:}") String localDir) {
    if (!endpoint.isBlank() && !bucket.isBlank() && !accessKey.isBlank() && !secret.isBlank() && !publicBase.isBlank())
      return new S3ImageStore(endpoint.strip(), bucket.strip(), accessKey.strip(), secret.strip(), publicBase.strip());
    if (!localDir.isBlank()) return new LocalImageStore(Path.of(localDir.strip()));
    return new ImageStore.NotConfigured();
  }
}
