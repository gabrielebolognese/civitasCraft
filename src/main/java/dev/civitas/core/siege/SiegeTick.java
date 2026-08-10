package dev.civitas.core.siege;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The Banner Bearer's aura, SPEC 29.3.
 *
 * <p>The only siege unit with an ongoing effect, and the only one that never attacks. SPEC 29.3:
 * "Grants Strength I and Speed I to attacking players within 12 blocks. The presence of an ominous
 * banner is thematically perfect for a siege, and a support unit that buffs players rather than
 * fighting keeps the emphasis where it belongs, on the players."
 *
 * <p>The effects are reapplied every sweep with a duration slightly longer than the interval, so
 * walking out of the aura lets them lapse within a second rather than needing to be stripped —
 * removing them by hand would also strip a potion the player drank themselves.
 */
public final class SiegeTick {

    private final Server server;
    private final SiegeSpawner spawner;
    private final SiegeCatalogue catalogue;

    public SiegeTick(Server server, SiegeSpawner spawner, SiegeCatalogue catalogue) {
        this.server = Objects.requireNonNull(server, "server");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    }

    /** How long each application lasts, in ticks. Longer than the sweep so there is no flicker. */
    public int effectTicks() {
        return 60;
    }

    public long sweepIntervalTicks() {
        return 40L;
    }

    public void sweep() {
        for (org.bukkit.World world : server.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof LivingEntity bearer)) {
                    continue;
                }
                Optional<SiegeUnitType> type = spawner.typeKeyOf(bearer)
                        .flatMap(catalogue::byKey);
                if (type.isEmpty() || !type.get().isSupport()) {
                    continue;
                }
                buffAround(bearer, type.get());
            }
        }
    }

    private void buffAround(LivingEntity bearer, SiegeUnitType type) {
        Integer ownerCity = spawner.cityIdOf(bearer).orElse(null);
        Integer warId = spawner.warIdOf(bearer).orElse(null);
        if (ownerCity == null || warId == null) {
            return;
        }
        double radius = type.buffRadius();

        for (Entity nearby : bearer.getNearbyEntities(radius, radius, radius)) {
            if (!(nearby instanceof Player player)) {
                continue;
            }
            // SPEC 29.3 buffs "attacking players", which is the side the bearer belongs to and
            // not merely anyone standing near it. A defender who charged the camp would
            // otherwise be handed Strength for their trouble.
            if (!onSameSide(warId, ownerCity, player.getUniqueId())) {
                continue;
            }
            // Ambient and iconless: SPEC 29.3 calls this a presence rather than a potion, and
            // a siege that fills a player's HUD with icons reads as something they drank.
            apply(player, PotionEffectType.STRENGTH);
            apply(player, PotionEffectType.SPEED);
        }
    }

    private void apply(Player player, PotionEffectType effect) {
        player.addPotionEffect(new PotionEffect(effect, effectTicks(), 0, true, false, false));
    }

    private boolean onSameSide(int warId, int ownerCityId, UUID player) {
        return sides != null && sides.isOnSameSide(warId, ownerCityId, player);
    }

    private SiegeTargeting.Wars sides;

    /** Reuses the resolver's war seam rather than defining a second one. */
    public void useWars(SiegeTargeting.Wars wars) {
        this.sides = Objects.requireNonNull(wars, "wars");
    }
}
