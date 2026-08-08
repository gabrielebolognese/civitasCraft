package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.core.combat.PvpPolicy;
import dev.civitas.lang.LangManager;
import dev.civitas.msg.Formats;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * The SPEC 37 grace periods, and the throttled refusal a blocked attacker reads.
 *
 * <p>The damage itself is cancelled by {@code EntityProtectionListener}, which already resolves
 * an arrow, a splash potion or a tamed wolf back to the player behind it. Duplicating that here
 * would be a second, worse copy of a resolution that took a milestone to get right.
 */
public final class PvpListener implements Listener {

    private final PvpPolicy policy;
    private final LangManager lang;

    public PvpListener(PvpPolicy policy, LangManager lang) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        policy.onJoin(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /**
     * SPEC 37's respawn grace.
     *
     * <p>At {@code MONITOR} because the grace starts whatever anything else decides about the
     * respawn, and stamping it earlier would let a cancelled respawn hand out immunity.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        policy.onRespawn(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Dropped rather than kept: a player who logs out mid-grace and returns an hour later
        // gets a fresh ten seconds, which is what the rule is for. Keeping it would let
        // somebody bank immunity by quitting.
        policy.forget(event.getPlayer().getUniqueId());
    }

    /** How long a player's own grace has left, in SPEC 23.7's shape, for the message. */
    public String graceLeft(Player player) {
        return Formats.duration(
                policy.graceRemaining(player.getUniqueId(), System.currentTimeMillis()));
    }

    /** The language files, so a caller can render a refusal without reaching past this. */
    public LangManager lang() {
        return lang;
    }
}
