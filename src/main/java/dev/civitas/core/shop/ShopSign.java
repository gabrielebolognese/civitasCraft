package dev.civitas.core.shop;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.Money;
import dev.civitas.util.Result;

/**
 * Reading the four lines of a chest-shop sign, SPEC 4.5.
 *
 * <pre>
 * [Shop]
 * 16                  quantity
 * B 100 : S 60        what a customer pays, what a customer receives
 * Notch               owner, filled in by the plugin
 * </pre>
 *
 * <p>{@code B} and {@code S} are written from the customer's point of view, which is the
 * convention every chest-shop plugin uses and the one a player arriving at a sign will
 * assume: {@code B 100} means "you may buy this for 100".
 *
 * <p>Prices go through {@link Money}, so a sign cannot smuggle in scientific notation, a
 * negative, or a third decimal place that the rest of the economy would refuse.
 */
public final class ShopSign {

    /** {@code B 100}, {@code S 60}, or {@code B 100 : S 60}, in either order, spaces free. */
    private static final Pattern OFFER = Pattern.compile(
            "^\\s*(?<firstKind>[bs])\\s*(?<firstPrice>[0-9.,]+)"
                    + "(?:\\s*[:;/|]\\s*(?<secondKind>[bs])\\s*(?<secondPrice>[0-9.,]+))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final ConfigManager configs;

    public ShopSign(ConfigManager configs) {
        this.configs = java.util.Objects.requireNonNull(configs, "configs");
    }

    /** The first line that marks a sign as a shop, from {@code economy.player-shops.sign-header}. */
    public String header() {
        return configs.get(ConfigFile.ECONOMY)
                .getString("player-shops.sign-header", "[Shop]");
    }

    /** Whether a first line claims to be a shop. Case-insensitive, so {@code [shop]} counts. */
    public boolean isHeader(String line) {
        return line != null && line.trim().equalsIgnoreCase(header());
    }

    /**
     * Parses lines two and three.
     *
     * @param quantityLine the second line
     * @param offerLine    the third line
     * @return the terms, or a failure naming the line that was wrong
     */
    public Result<ShopTerms> parse(String quantityLine, String offerLine) {
        Result<Integer> quantity = parseQuantity(quantityLine);
        if (quantity instanceof Result.Failure<Integer> failure) {
            return Result.propagate(failure);
        }

        if (offerLine == null || offerLine.isBlank()) {
            return Result.failure("OFFER_MISSING", "shop.invalid-offer");
        }
        Matcher matcher = OFFER.matcher(offerLine);
        if (!matcher.matches()) {
            return Result.failure("OFFER_INVALID", "shop.invalid-offer");
        }

        BigDecimal buy = null;
        BigDecimal sell = null;

        Result<BigDecimal> first = price(matcher.group("firstPrice"));
        if (first instanceof Result.Failure<BigDecimal> failure) {
            return Result.propagate(failure);
        }
        boolean firstIsBuy = isBuy(matcher.group("firstKind"));
        if (firstIsBuy) {
            buy = first.orElseThrow();
        } else {
            sell = first.orElseThrow();
        }

        if (matcher.group("secondKind") != null) {
            boolean secondIsBuy = isBuy(matcher.group("secondKind"));
            if (secondIsBuy == firstIsBuy) {
                return Result.failure("OFFER_DUPLICATE", "shop.invalid-offer");
            }
            Result<BigDecimal> second = price(matcher.group("secondPrice"));
            if (second instanceof Result.Failure<BigDecimal> failure) {
                return Result.propagate(failure);
            }
            if (secondIsBuy) {
                buy = second.orElseThrow();
            } else {
                sell = second.orElseThrow();
            }
        }

        // A shop that buys for more than it sells for is a money pump: anyone could sell
        // into it and buy straight back out at a profit until the owner is bankrupt.
        if (buy != null && sell != null && sell.compareTo(buy) > 0) {
            return Result.failure("OFFER_INVERTED", "shop.inverted-prices",
                    Map.of("buy", buy.toPlainString(), "sell", sell.toPlainString()));
        }

        return Result.success(new ShopTerms(quantity.orElseThrow(), buy, sell));
    }

    private Result<Integer> parseQuantity(String line) {
        if (line == null || line.isBlank()) {
            return Result.failure("QUANTITY_MISSING", "shop.invalid-quantity");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(line.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return Result.failure("QUANTITY_INVALID", "shop.invalid-quantity");
        }
        if (parsed <= 0) {
            return Result.failure("QUANTITY_INVALID", "shop.invalid-quantity");
        }
        int max = configs.get(ConfigFile.ECONOMY)
                .getInt("player-shops.max-quantity-per-transaction", 2304);
        if (parsed > max) {
            return Result.failure("QUANTITY_TOO_LARGE", "shop.quantity-too-large",
                    Map.of("max", String.valueOf(max)));
        }
        return Result.success(parsed);
    }

    private static Result<BigDecimal> price(String raw) {
        Result<BigDecimal> parsed = Money.parse(raw);
        if (parsed instanceof Result.Failure<BigDecimal>) {
            return Result.failure("PRICE_INVALID", "shop.invalid-price");
        }
        return parsed;
    }

    private static boolean isBuy(String kind) {
        return kind.toLowerCase(Locale.ROOT).startsWith("b");
    }
}
