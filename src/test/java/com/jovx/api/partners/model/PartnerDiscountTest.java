package com.jovx.api.partners.model;
import com.jovx.api.partners.model.PartnerModels.PartnerDiscount;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Business examples: percentages are not money, and may never undercut the Partner minimum. */
class PartnerDiscountTest {
    @Test void restaurantAndShopProductsCannotUndercutTheirSharedOwnerMinimum() {
        var policy = new PartnerDiscount(new BigDecimal("10.00"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed(new BigDecimal("8")));
        assertDoesNotThrow(() -> policy.requireAllowed(new BigDecimal("10")));
        assertDoesNotThrow(() -> policy.requireAllowed(new BigDecimal("15")));
    }
    @Test void fractionalDiscountMustNotBeRoundedUpToPassTheMinimum() {
        var policy = new PartnerDiscount(new BigDecimal("10.25"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed(new BigDecimal("10.24")));
        assertDoesNotThrow(() -> policy.requireAllowed(new BigDecimal("10.25")));
    }
    @Test void negativeAndOverOneHundredPercentAreNotValidDiscounts() {
        assertThrows(IllegalArgumentException.class, () -> new PartnerDiscount(new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> new PartnerDiscount(new BigDecimal("101")));
        var policy = new PartnerDiscount(BigDecimal.ZERO);
        assertDoesNotThrow(() -> policy.requireAllowed(new BigDecimal("100")));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed(new BigDecimal("101")));
    }
}
