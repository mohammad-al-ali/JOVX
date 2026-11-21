package com.jovx.api.wallets.service;

import static com.jovx.api.wallets.model.WalletModels.*;

import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.wallets.repository.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decision 23: every day at 9:00 Damascus time, each active partner whose spendable balance is below
 * the threshold is told once (a second run the same day sends nothing).
 */
@Service
@Profile("local-postgres")
public class LowBalanceAlerts {
  private static final ZoneId DAMASCUS = ZoneId.of("Asia/Damascus");
  private final WalletRepository repository;
  private final PartnerLookup partners;
  private final WalletEvents events;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean enabled;

  public LowBalanceAlerts(WalletRepository repository, PartnerLookup partners, WalletEvents events,
      @Qualifier("walletTransactions") TransactionTemplate tx, Clock clock,
      @Value("${jovx.wallets.low-balance-alerts-enabled:true}") boolean enabled) {
    this.repository = repository; this.partners = partners; this.events = events; this.tx = tx; this.clock = clock;
    this.enabled = enabled;
  }

  @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Damascus")
  void daily() {
    if (enabled) run();
  }

  /** Returns how many partners were alerted. */
  public int run() {
    Integer sent = tx.execute(s -> {
      Instant now = clock.instant();
      var today = now.atZone(DAMASCUS).toLocalDate();
      long threshold = repository.settings().lowBalanceThreshold();
      int count = 0;
      for (var p : partners.all()) {
        if (!p.active()) continue;
        long balance = repository.balance(p.id());
        if (balance >= threshold) continue;
        if (!repository.recordLowBalanceAlert(p.id(), today, balance)) continue;
        events.lowBalance(p.id(), balance, threshold, now);
        count++;
      }
      return count;
    });
    return sent == null ? 0 : sent;
  }
}
