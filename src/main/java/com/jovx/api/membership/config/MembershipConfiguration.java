package com.jovx.api.membership.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("local-postgres")
public class MembershipConfiguration {
  @Bean("membershipTransactions")
  TransactionTemplate transactions(DataSource source) {
    return new TransactionTemplate(new JdbcTransactionManager(source));
  }
}
