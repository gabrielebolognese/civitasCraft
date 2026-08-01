package dev.civitas.core.shop;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * What a player shop sign offers, SPEC 4.5.
 *
 * <p>Both prices are for the whole {@code quantity}, not per item, because that is what the
 * sign shows and what the customer sees before clicking.
 *
 * @param quantity     how many items one transaction moves
 * @param customerPays the price a customer pays to take items, from a {@code B} line, or
 *                     null if the shop does not sell
 * @param customerGets the price a customer receives for giving items, from an {@code S}
 *                     line, or null if the shop does not buy
 */
public record ShopTerms(int quantity, BigDecimal customerPays, BigDecimal customerGets) {

    public ShopTerms {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }
        if (customerPays == null && customerGets == null) {
            throw new IllegalArgumentException("a shop must buy, sell, or both");
        }
    }

    public Optional<BigDecimal> buyPrice() {
        return Optional.ofNullable(customerPays);
    }

    public Optional<BigDecimal> sellPrice() {
        return Optional.ofNullable(customerGets);
    }

    public boolean sellsToCustomers() {
        return customerPays != null;
    }

    public boolean buysFromCustomers() {
        return customerGets != null;
    }

    /** The unit price a customer pays, for display next to the market price. */
    public Optional<BigDecimal> unitBuyPrice() {
        return buyPrice().map(price -> price.divide(BigDecimal.valueOf(quantity),
                dev.civitas.storage.SqlDialect.MONEY_SCALE, java.math.RoundingMode.DOWN));
    }

    /** The unit price a customer receives. */
    public Optional<BigDecimal> unitSellPrice() {
        return sellPrice().map(price -> price.divide(BigDecimal.valueOf(quantity),
                dev.civitas.storage.SqlDialect.MONEY_SCALE, java.math.RoundingMode.DOWN));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ShopTerms terms)) {
            return false;
        }
        return quantity == terms.quantity
                && same(customerPays, terms.customerPays)
                && same(customerGets, terms.customerGets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(quantity,
                customerPays == null ? null : customerPays.stripTrailingZeros(),
                customerGets == null ? null : customerGets.stripTrailingZeros());
    }

    /** {@code BigDecimal.equals} calls 10 and 10.00 different; for money they are not. */
    private static boolean same(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }
}
