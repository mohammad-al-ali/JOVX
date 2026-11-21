/**
 * Point of sale: member scan, product invoices and free sales, receipts, history and the partner dashboard.
 * One sale is one local transaction: wallet debit, member points, stock and the receipt succeed or fail together.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "common", "security::api", "partners::api", "membership::api", "catalog::api", "wallets::api", "points::api"})
package com.jovx.api.sales;
