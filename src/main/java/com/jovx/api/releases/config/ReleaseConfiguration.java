package com.jovx.api.releases.config;

import com.jovx.api.releases.service.HttpManifestClient;
import com.jovx.api.releases.service.ManifestClient;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("local-postgres")
public class ReleaseConfiguration {
  @Bean("releaseTransactions")
  TransactionTemplate transactions(DataSource source) {
    return new TransactionTemplate(new JdbcTransactionManager(source));
  }

  /** Empty properties disable import (MANIFEST_NOT_CONFIGURED); checks keep working. */
  @Bean
  @ConditionalOnMissingBean(ManifestClient.class)
  ManifestClient manifestClient(
      @Value("${jovx.releases.manifest-base-url:}") String base,
      @Value("${jovx.releases.download-url-prefix:}") String downloadPrefix) {
    return new HttpManifestClient(base, downloadPrefix);
  }
}
