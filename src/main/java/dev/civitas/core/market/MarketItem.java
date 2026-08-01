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
        double elasticity) {

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
}
