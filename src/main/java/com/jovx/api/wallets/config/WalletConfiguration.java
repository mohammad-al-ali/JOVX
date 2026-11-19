package com.jovx.api.wallets.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("local-postgres")
public class WalletConfiguration {
  @Bean("walletTransactions")
  TransactionTemplate transactions(DataSource source) {
    return new TransactionTemplate(new JdbcTransactionManager(source));
  }
}
