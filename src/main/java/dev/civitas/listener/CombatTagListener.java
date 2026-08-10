package dev.civitas.listener;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.combat.CombatTag;
import dev.civitas.core.combat.PvpPolicy;
import dev.civitas.lang.LangManager;
import dev.civitas.msg.Channel;
import dev.civitas.msg.Formats;
import dev.civitas.msg.Messenger;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * SPEC 33.8's tag, applied and shown.
 *
 * <p>Tagging is one rule and three consequences. The rule is "deal or receive damage from another
 * player". The consequences — no teleport, no vault, no safe logout — live where they are
 * enforced; this class sets the tag, shows it, and handles the logout.
 *
 * <h2>The countdown is required, not decorative</h2>
 *
 * <p>SPEC 33.8: "The remaining tag time is shown continuously on the action bar, with a distinct
 * colour for the war duration. A player must never be surprised that a teleport was refused."
 * A tag a player cannot see is indistinguishable from a broken teleport command, and they will
 * report it as one.
 *
 * <h2>Combat logging</h2>
 *
 * <p>SPEC 33.9 case 121: "Player logs out at 29 seconds of a 30-second tag: killed. There is no
 * near-miss grace." The whole value of a logout rule is that it has no edge to play for, so this
 * one does not soften as the timer runs down.
 */
public final class CombatTagListener implements Listener {

    private final CombatTag tags;
    private final PvpPolicy pvp;
    private final ConfigManager configs;
    private final LangManager lang;
    private final Messenger messenger;
    private final Logger logger;

    public CombatTagListener(CombatTag tags, PvpPolicy pvp, ConfigManager configs,
                             LangManager lang, Messenger messenger, Logger logger) {
        this.tags = Objects.requireNonNull(tags, "tags");
        this.pvp = Objects.requireNonNull(pvp, "pvp");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.messenger = Objects.requireNonNull(messenger, "messenger");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Applying it
    // ==================================================================================

    /**
     * Any damage between two players tags both, SPEC 33.8.
     *
     * <p>{@code MONITOR} and {@code ignoreCancelled}: a hit that protection refused never
     * happened, and tagging on it would let somebody lock a stranger out of teleporting by
     * swinging at them inside a claim where they cannot be hurt.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        var at = victim.getLocation();
        boolean war = at.getWorld() != null && pvp.isWarPvpAllowed(
                attacker.getUniqueId(), victim.getUniqueId(),
                at.getWorld().getName(), at.getBlockX() >> 4, at.getBlockZ() >> 4);

        tags.hit(attacker.getUniqueId(), victim.getUniqueId(), war, System.currentTimeMillis());
    }

    /**
     * Who is answerable for this damage.
     *
     * <p>An arrow, a splash potion and a thrown trident all arrive as a projectile whose shooter
     * is the player. Reading only the direct damager would let a bow user fight without ever
     * being tagged, which is the escape the tag exists to close.
     */
    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player direct) {
            return direct;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // ==================================================================================
    // Showing it, SPEC 33.8
    // ==================================================================================

    /** Called on a timer. Draws the remaining tag on the action bar for everyone tagged. */
    public int showCountdowns(Iterable<? extends Player> online, long now) {
        if (!countdownEnabled()) {
            return 0;
        }
        int shown = 0;
        for (Player player : online) {
            long remaining = tags.remaining(player.getUniqueId(), now);
            if (remaining <= 0) {
                continue;
            }
            String key = tags.isWarTag(player.getUniqueId(), now)
                    ? "combat.tag-countdown-war"
                    : "combat.tag-countdown";
            // ToggleCategory.WAR, which SPEC 23.6 locks on. A countdown a player can mute is
            // a teleport refusal they cannot explain, which is the surprise SPEC 33.8 forbids.
            messenger.send(player.getUniqueId(), player, dev.civitas.msg.ToggleCategory.WAR,
                    Channel.ACTION_BAR, key,
                    LangManager.placeholder("time", Formats.duration(remaining)));
            shown++;
        }
        return shown;
    }

    // ==================================================================================
    // Combat logging, SPEC 33.9 case 121
    // ==================================================================================

    /**
     * Logging out while tagged kills the character.
     *
     * <p>SPEC 33.8: "Logging out kills the character; items drop under the applicable rule."
     * The drop rule is {@code DeathPolicy}'s, applied by the death listener when the kill lands
     * — this only causes the death, so there is one place that decides what a death costs.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();

        if (!tags.isTagged(player.getUniqueId(), now)) {
            tags.clear(player.getUniqueId());
            return;
        }
        if (!killOnLogout()) {
            tags.clear(player.getUniqueId());
            return;
        }

        // No near-miss grace, SPEC 33.9 case 121. A rule with an edge to play for is a rule
        // players will play for.
        try {
            player.setHealth(0);
            logger.fine(() -> player.getName() + " combat logged while tagged and was killed.");
        } catch (IllegalArgumentException e) {
            logger.warning("Could not kill " + player.getName() + " for combat logging: " + e);
        }
        tags.clear(player.getUniqueId());
    }

    /** A death ends the tag: it has done its job either way. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        tags.clear(event.getEntity().getUniqueId());
    }

    // ==================================================================================
    // Configuration
    // ==================================================================================

    private boolean countdownEnabled() {
        return configs.get(ConfigFile.COMBAT).getBoolean("pvp.tag-actionbar", true);
    }

    private boolean killOnLogout() {
        return configs.get(ConfigFile.COMBAT).getBoolean("pvp.kill-on-combat-logout", true);
    }

    /** Exposed so a quit handler elsewhere can drop the tag without reaching past this class. */
    public void forget(UUID player) {
        tags.clear(player);
    }
}
