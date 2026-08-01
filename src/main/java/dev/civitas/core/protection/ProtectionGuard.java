package dev.civitas.core.protection;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.core.claim.ChunkKey;
import dev.civitas.lang.LangManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The bridge between Bukkit events and {@link ProtectionService}.
 *
 * <p>Listeners hold one of these and ask it a single question. It resolves the bypass
 * permission, converts a {@link Location} into the world and chunk the service wants, and
 * tells the player why they were refused.
 *
 * <p>Refusals are throttled per player. Walking into a claim and holding down left-click
 * produces one denial per tick, and twenty identical lines a second is worse than silence.
 */
public final class ProtectionGuard {

    /** The SPEC 10 node that lets an admin build anywhere. */
    public static final String BYPASS_PERMISSION = "civitas.bypass.claim";

    private final ProtectionService protection;
    private final LangManager lang;

    /** When each player was last told they could not do something. */
    private final Map<UUID, Long> lastDenial = new ConcurrentHashMap<>();

    public ProtectionGuard(ProtectionService protection, LangManager lang) {
        this.protection = Objects.requireNonNull(protection, "protection");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    public ProtectionService service() {
        return protection;
    }

    /**
     * Whether the player may act here, telling them if not.
     *
     * @return true if the event should proceed
     */
    public boolean allows(Player player, Location location, ProtectionAction action) {
        ProtectionDecision decision = decide(player, location, action);
        if (decision.denied()) {
            notify(player, decision);
        }
        return decision.allowed();
    }

    /**
     * The same question without a message.
     *
     * <p>For events that fire many times for one player action, such as an explosion's block
     * list, where a message per block would be absurd.
     */
    public boolean allowsSilently(Player player, Location location, ProtectionAction action) {
        return decide(player, location, action).allowed();
    }

    private ProtectionDecision decide(Player player, Location location, ProtectionAction action) {
        if (location == null || location.getWorld() == null) {
            return ProtectionDecision.ALLOWED;
        }
        return protection.check(
                player.getUniqueId(),
                player.hasPermission(BYPASS_PERMISSION),
                location.getWorld().getName(),
                ChunkKey.toChunk(location.getBlockX()),
                ChunkKey.toChunk(location.getBlockZ()),
                action);
    }

    /** Sends a refusal on the action bar, at most once per cooldown. */
    public void notify(Player player, ProtectionDecision decision) {
        if (decision.messageKey() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastDenial.get(player.getUniqueId());
        if (last != null && now - last < protection.denyMessageCooldownMillis()) {
            return;
        }
        lastDenial.put(player.getUniqueId(), now);

        player.sendActionBar(lang.get(decision.messageKey(),
                LangManager.placeholders(decision.placeholders())));
    }

    /** Forgets a player's throttle state, so a rejoin starts clean. */
    public void forget(UUID player) {
        lastDenial.remove(player);
    }
}
