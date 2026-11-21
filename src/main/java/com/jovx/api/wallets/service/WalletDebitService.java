package com.jovx.api.wallets.service;

import com.jovx.api.wallets.api.WalletCredits;
import com.jovx.api.wallets.api.WalletDebits;
import com.jovx.api.wallets.repository.WalletRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local-postgres")
public class WalletDebitService implements WalletDebits, WalletCredits {
  private final WalletRepository repository;

  public WalletDebitService(WalletRepository repository) {
    this.repository = repository;
  }

  public boolean debitSalePoints(UUID partnerId, long points, UUID saleId, UUID actor, Instant now) {
    if (points <= 0) return true;
    var wallet = repository.lockWallet(partnerId, now);
    if (wallet.balance() < points) return false;
    repository.debitForSale(wallet, points, saleId, actor, now);
    return true;
  }

  public void returnSalePoints(UUID partnerId, long points, UUID saleId, UUID actor, Instant now) {
    if (points <= 0) return;
    repository.creditForCancelledSale(repository.lockWallet(partnerId, now), points, saleId, actor, now);
  }
}
