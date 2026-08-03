package dev.civitas.listener;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityHall;
import dev.civitas.gui.menus.MainMenu;
import dev.civitas.lang.LangManager;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * The City Hall block, SPEC 8.1.
 *
 * <p>Right-click opens the hub, breaking it is refused below Co-Mayor, and placing one stamps
 * it. The one-per-city rule is enforced on placement rather than by sweeping the world: a
 * second hall does nothing, so the worst a player achieves by placing one is wasting a block.
 */
public final class CityHallListener implements Listener {

    private final Supplier<CivitasServices> services;
    private final CityHall halls;
    private final LangManager lang;

    public CityHallListener(Supplier<CivitasServices> services, CityHall halls, LangManager lang) {
        this.services = Objects.requireNonNull(services, "services");
        this.halls = Objects.requireNonNull(halls, "halls");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    // ==================================================================================
    // Opening
    // ==================================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        CivitasServices current = services.get();
        if (current == null) {
            return;
        }

        Optional<Integer> cityId = halls.cityIdOf(event.getClickedBlock());
        if (cityId.isEmpty()) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);

        Player player = event.getPlayer();
        Optional<City> city = current.registry().city(cityId.get());
        if (city.isEmpty()) {
            lang.send(player, "city.hall.city-gone");
            return;
        }
        if (!city.get().isMember(player.getUniqueId())) {
            // Somebody else's hall. Show them whose, rather than opening it.
            lang.send(player, "city.hall.not-yours",
                    LangManager.placeholder("city", city.get().name()));
            return;
        }

        new MainMenu(current.menus(), current, player, city.get()).open();
    }

    // ==================================================================================
    // Placing
    // ==================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        CivitasServices current = services.get();
        if (current == null) {
            return;
        }
        Optional<Integer> stamped = halls.cityIdOf(event.getItemInHand());
        if (stamped.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        Optional<City> city = current.registry().city(stamped.get());
        if (city.isEmpty() || !city.get().isMember(player.getUniqueId())) {
            lang.send(player, "city.hall.not-yours",
                    LangManager.placeholder("city", city.map(City::name).orElse("?")));
            return;
        }

        if (halls.mark(event.getBlock(), city.get())) {
            lang.send(player, "city.hall.placed");
        }
    }

    // ==================================================================================
    // Breaking
    // ==================================================================================

    /**
     * SPEC 8.1: nobody below Co-Mayor, and nobody at all during a war.
     *
     * <p>{@link EventPriority#LOW} so it runs alongside land protection rather than after it;
     * a hall standing in a claim the breaker may build in is still protected by this.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        CivitasServices current = services.get();
        if (current == null) {
            return;
        }
        Block block = event.getBlock();
        Optional<Integer> cityId = halls.cityIdOf(block);
        if (cityId.isEmpty()) {
            return;
        }

        Optional<City> city = current.registry().city(cityId.get());
        if (city.isEmpty()) {
            // Its city is gone; the block is just a block now.
            return;
        }
        if (!halls.mayBreak(city.get(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            lang.send(event.getPlayer(), "city.hall.cannot-break");
        }
    }
}
