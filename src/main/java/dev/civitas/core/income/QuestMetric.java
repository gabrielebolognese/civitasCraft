package dev.civitas.core.income;

/**
 * What a quest or challenge counts.
 *
 * <p>An enum rather than free text in config, so a typo in {@code economy.yml} is caught when
 * the pool loads instead of producing a quest nobody can ever finish. Adding a metric means
 * adding a case here and somewhere that reports it, which is the right amount of friction:
 * every one of these has to be fed by a listener.
 */
public enum QuestMetric {

    /** Crops harvested, SPEC 13.1 Farming. */
    HARVEST_CROPS,

    /** Animals bred, SPEC 13.1 Farming. */
    BREED_ANIMALS,

    /** Blocks placed of any type, SPEC 13.1 Building. */
    PLACE_BLOCKS,

    /** Items crafted, SPEC 13.1 Building. */
    CRAFT_ITEMS,

    /** Ore blocks mined, SPEC 13.1 Mining. */
    MINE_ORE,

    /** Coins of value sold to the server market, SPEC 13.1 Trading. */
    MARKET_SELL_VALUE,

    /** Coins deposited into the city treasury, SPEC 13.1 Social. */
    TREASURY_DEPOSIT,

    /** Distinct biomes entered, SPEC 13.1 Exploration. */
    VISIT_BIOMES,

    /** Blocks broken of any type, for SPEC 13.2's city-wide challenges. */
    BREAK_BLOCKS;

    /**
     * Whether this metric counts money rather than things.
     *
     * <p>Money metrics are reported in whole coins, so a target of 5,000 means 5,000 C and
     * not five thousand separate sales.
     */
    public boolean isMoney() {
        return this == MARKET_SELL_VALUE || this == TREASURY_DEPOSIT;
    }
}
