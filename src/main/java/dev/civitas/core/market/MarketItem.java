package dev.civitas.core.market;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One tradeable line of the SPEC 4.4 market table.
 *
 * <p>An item absent from {@code economy.yml} is not traded by the server at all, which is
 * how the SPEC 4.4 exclusion rule is enforced: cobblestone, mob drops and iron-farm output
 * are simply never listed, so there is no code path that buys them.
 *
 * @param material    the Bukkit material name, as written in config
 * @param basePrice   the reference value the curve is a multiple of
 * @param targetStock the equilibrium supply the price is measured against
 * @param elasticity  how sharply the price responds to being away from target
 */
public record MarketItem(
        String material,
        BigDecimal basePrice,
        int targetStock,
        double elasticity,
        boolean serverBuys) {

    /**
     * A sell-only entry, SPEC 21.6's builder catalogue.
     *
     * <p>The server sells it and will never buy it, so none of SPEC 21.10.1's assertions
     * apply: "every item the server buys is a potential money faucet. Every item the server
     * sells is a money sink and carries no exploit risk at all."
     */
    public static MarketItem sellOnly(String material, BigDecimal basePrice, int targetStock,
                                      double elasticity) {
        return new MarketItem(material, basePrice, targetStock, elasticity, false);
    }

    /** An entry the server buys as well as sells, SPEC 21.9's narrow whitelist. */
    public static MarketItem tradedBothWays(String material, BigDecimal basePrice,
                                            int targetStock, double elasticity) {
        return new MarketItem(material, basePrice, targetStock, elasticity, true);
    }

    public MarketItem {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(basePrice, "basePrice");
        if (targetStock <= 0) {
            throw new IllegalArgumentException(
                    "target-stock must be positive for " + material + ", was " + targetStock);
        }
        if (basePrice.signum() <= 0) {
            throw new IllegalArgumentException(
                    "base-price must be positive for " + material + ", was " + basePrice);
        }
        if (elasticity <= 0) {
            throw new IllegalArgumentException(
                    "elasticity must be positive for " + material + ", was " + elasticity);
        }
    }

    /** A copy at a new base price, for SPEC 9.4.4's {@code /ca market setprice}. */
    public MarketItem withBasePrice(BigDecimal newBasePrice) {
        return new MarketItem(material, newBasePrice, targetStock, elasticity, serverBuys);
    }
}
