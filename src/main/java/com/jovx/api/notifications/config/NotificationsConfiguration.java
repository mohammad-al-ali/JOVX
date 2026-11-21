package com.jovx.api.notifications.config;

import com.jovx.api.notifications.push.FcmPushSender;
import com.jovx.api.notifications.push.PushSender;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("local-postgres")
@EnableScheduling
public class NotificationsConfiguration {
  /** Same DataSource as the other modules, so Notifier calls join the business transaction. */
  @Bean("notificationTransactions")
  TransactionTemplate transactions(DataSource source) {
    return new TransactionTemplate(new JdbcTransactionManager(source));
  }

  /** FIREBASE_SERVICE_ACCOUNT_BASE64 unset: push is off and every notification stays in the inbox only. */
  @Bean
  PushSender pushSender(@Value("${jovx.notifications.firebase-service-account:}") String serviceAccount, Clock clock) {
    return serviceAccount == null || serviceAccount.isBlank() ? PushSender.NONE : FcmPushSender.fromBase64(serviceAccount, clock);
  }
}
