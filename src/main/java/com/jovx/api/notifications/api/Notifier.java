package com.jovx.api.notifications.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Raises a notification for one account inside the CALLER's transaction: the inbox row and the push
 * outbox row commit or roll back with the business change; the push itself is sent after commit.
 * A kind switched off by SUPER_ADMIN is silently skipped.
 */
public interface Notifier {
  /** Member: a purchase receipt (decision 15). data: saleId. */
  String SALE_RECEIPT = "SALE_RECEIPT";
  /** Member: a partner asks to cancel a purchase. data: saleId, cancellationId. */
  String SALE_CANCEL_REQUESTED = "SALE_CANCEL_REQUESTED";
  /** Partner: the member accepted or rejected a cancellation. data: saleId. */
  String SALE_CANCEL_RESULT = "SALE_CANCEL_RESULT";
  /** Partner: the wallet is below the threshold (daily, 9:00 Damascus). */
  String LOW_BALANCE = "LOW_BALANCE";
  /** Partner: a top-up or refund request was approved or rejected. data: requestId. */
  String WALLET_REQUEST_RESULT = "WALLET_REQUEST_RESULT";
  /** Partner: a product was approved, rejected or hidden. data: productId. */
  String PRODUCT_REVIEWED = "PRODUCT_REVIEWED";
  /** Partner: a message from administration. data: messageId. */
  String ADMIN_MESSAGE = "ADMIN_MESSAGE";

  void notify(UUID account, String kind, String title, String body, Map<String, String> data, Instant now);
}
