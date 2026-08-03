package dev.civitas.core.income;

/**
 * The six things SPEC 4.2.1 counts as a sign of life.
 *
 * <p>The list is deliberately short and deliberately varied. What makes it work is not any
 * one entry but the requirement that <em>three distinct</em> kinds happen in one interval: an
 * AFK machine can usually fake one of these, occasionally two, and essentially never three
 * without a player at the keyboard.
 */
public enum ActivityKind {

    /** Cumulative distance past {@code economy.income.stipend.move-distance-blocks}. */
    MOVED,

    /** Broke a block. */
    BROKE_BLOCK,

    /** Placed a block. */
    PLACED_BLOCK,

    /** Opened any inventory: a chest, a crafting table, their own. */
    OPENED_INVENTORY,

    /** Said something, or ran a command. */
    SPOKE,

    /** Damaged an entity, or was damaged by one. */
    FOUGHT
}
