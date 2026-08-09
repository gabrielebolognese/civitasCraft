package dev.civitas.core.defense;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The four things a standing Warden needs every tick, SPEC 28.3, 28.7 and 28.8.
 *
 * <p>Separate from {@link DefenseTick} because none of it applies to any other unit: no other unit
 * has an anger map to own, a blindness aura to replace, a single chunk to be confined to, or an
 * emergence to announce. Keeping them apart also keeps SPEC 30.1's promise honest —
 * {@link TargetingRule} is still the only thing that decides <em>whether</em> the Warden may attack
 * a player, and this only carries that decision into a mob whose brain does not read
 * {@code setTarget}.
 */
public final class WardenTick {

    private final WardenRegistry wardens;
    private final DefenseRegistry units;
    private final DefenseCatalogue catalogue;
    private final CityRegistry cities;
    private final UnitStates states;
    private final WardenSuppression suppression;

    /** Who has already been told a Warden woke up for them, so the title fires once per alert. */
    private final Map<Integer, UUID> announced = new ConcurrentHashMap<>();

    public WardenTick(WardenRegistry wardens, DefenseRegistry units, DefenseCatalogue catalogue,
                      CityRegistry cities, UnitStates states, WardenSuppression suppression) {
        this.wardens = Objects.requireNonNull(wardens, "wardens");
        this.units = Objects.requireNonNull(units, "units");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.states = Objects.requireNonNull(states, "states");
        this.suppression = Objects.requireNonNull(suppression, "suppression");
    }

    /**
     * One pass over every standing Warden, which on a live server is a handful at most.
     *
     * @return how many were materialised and therefore did anything
     */
    public int tick(long now) {
        int seen = 0;
        for (CityWarden.Owned owned : wardens.all()) {
            if (owned.isRecovering(now)) {
                continue;
            }
            Optional<LivingEntity> entity = units.entityOf(owned.unitId());
            if (entity.isEmpty() || !(entity.get() instanceof Warden warden)) {
                announced.remove(owned.unitId());
                continue;
            }
            seen++;
            Player target = alertedTarget(owned.unitId(), now);

            // SPEC 28.8, and the order matters: anger is cleared from everything that is not the
            // permitted target before it is set on the one that is, so a Warden never holds two.
            suppression.suppressAnger(warden, target, nearbyPlayers(warden));
            confine(warden, owned);
            applyDarkness(warden, target);
            announce(owned, target);
        }
        return seen;
    }

    /**
     * Everyone close enough to have provoked it, which is who its anger is cleared over.
     *
     * <p>The vanilla vibration radius rather than the unit's own range: a Warden hears further
     * than it sees, and an entry in the anger map that nothing clears is a target waiting to be
     * acted on the moment the plugin's chosen one leaves.
     */
    private static java.util.List<Player> nearbyPlayers(Warden warden) {
        java.util.List<Player> found = new java.util.ArrayList<>();
        for (org.bukkit.entity.Entity entity : warden.getNearbyEntities(
                VIBRATION_BLOCKS, VIBRATION_BLOCKS, VIBRATION_BLOCKS)) {
            if (entity instanceof Player player) {
                found.add(player);
            }
        }
        return found;
    }

    /** How far a vanilla Warden listens, which is further than SPEC 28.3 lets it walk. */
    private static final double VIBRATION_BLOCKS = 16;

    /**
     * The one player SPEC 30.1's table has allowed this unit to attack, if any.
     *
     * <p>Read from {@link UnitStates} rather than decided here. That is what keeps SPEC 30.1's
     * "no unit-specific targeting logic anywhere else" true of the unit most tempted to break it.
     */
    private Player alertedTarget(int unitId, long now) {
        return states.alertedTarget(unitId, now).map(Bukkit::getPlayer).orElse(null);
    }

    // ==================================================================================
    // SPEC 28.3, confinement
    // ==================================================================================

    /**
     * "Confined to the core chunk plus 6 blocks."
     *
     * <p>An active leash rather than an assumption. A freshly spawned Warden runs its own vanilla
     * sniffing and listening behaviour and will walk toward whatever it last heard, so a Warden
     * with no target does not stay put on its own.
     */
    private void confine(Warden warden, CityWarden.Owned owned) {
        Optional<City> city = cities.city(owned.cityId());
        Optional<DefenseUnit> unit = units.byId(owned.unitId());
        if (city.isEmpty() || unit.isEmpty()) {
            return;
        }
        Location at = warden.getLocation();
        if (at.getWorld() == null || !at.getWorld().getName().equals(city.get().coreWorld())) {
            return;
        }
        if (!CityWarden.outsideConfinement(city.get().coreChunkX(), city.get().coreChunkZ(),
                at.getBlockX(), at.getBlockZ(), catalogue.wardenLeashBlocks())) {
            return;
        }
        unit.get().location().ifPresent(warden::teleport);
    }

    // ==================================================================================
    // SPEC 28.3 and 28.8, darkness
    // ==================================================================================

    /**
     * "Darkness aura: 10 block radius, ALERTED only", applied by the plugin to one player.
     *
     * <p>The vanilla twenty-block aura is cancelled by {@code WardenListener}; this is what
     * replaces it. Applying it to the alerted target alone is the whole of SPEC 28.3's change:
     * a contest voter walking past a Warden in a city they are visiting sees nothing at all,
     * which SPEC 25.2 Rule 2 requires.
     */
    private void applyDarkness(Warden warden, Player target) {
        if (target == null || target.getWorld() != warden.getWorld()) {
            return;
        }
        if (!CityWarden.withinDarkness(target.getLocation().distance(warden.getLocation()),
                catalogue.wardenDarknessRadius())) {
            return;
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,
                catalogue.wardenDarknessDurationTicks(), catalogue.wardenDarknessAmplifier(),
                true, false, false));
    }

    // ==================================================================================
    // SPEC 30.4, warden.emerged
    // ==================================================================================

    /**
     * Tells a trespasser, once, that the thing in front of them has noticed.
     *
     * <p>SPEC 30.4 gives this a title and a sound, and SPEC 26.2 gives the reason: "no player is
     * ever killed without being told, in plain language, that they are about to be. This matters
     * most for the Warden, which is lethal to an unarmored player." At SPEC 28.4's 10 damage that
     * is two hits, so the warning is worth more here than anywhere else in the plugin.
     */
    private void announce(CityWarden.Owned owned, Player target) {
        if (target == null) {
            announced.remove(owned.unitId());
            return;
        }
        UUID told = announced.get(owned.unitId());
        if (target.getUniqueId().equals(told)) {
            return;
        }
        announced.put(owned.unitId(), target.getUniqueId());
        onEmerged.accept(owned, target);
    }

    private BiConsumer<CityWarden.Owned, Player> onEmerged = (owned, player) -> { };

    /** Wired to the messenger, which owns SPEC 23.4's channels and the title cap. */
    public void onEmerged(BiConsumer<CityWarden.Owned, Player> notifier) {
        this.onEmerged = Objects.requireNonNull(notifier, "notifier");
    }

    public void forgetUnit(int unitId) {
        announced.remove(unitId);
    }
}
