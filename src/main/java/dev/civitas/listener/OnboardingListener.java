package dev.civitas.listener;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.onboarding.GuideBook;
import dev.civitas.core.onboarding.OnboardingService;
import dev.civitas.core.onboarding.StarterStep;
import dev.civitas.lang.LangManager;
import dev.civitas.msg.Channel;
import dev.civitas.msg.Messenger;
import dev.civitas.msg.ToggleCategory;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

/**
 * SPEC 34.2's first session and the triggers behind SPEC 34.3's chain.
 *
 * <h2>Nothing here is a gate</h2>
 *
 * <p>SPEC 34.2: "No forced tutorial, ever. The player can walk away from all of it. Everything
 * above is a nudge, not a gate." So this listener cancels no event, blocks no movement and shows no
 * screen a player has to dismiss. It watches, pays and gets out of the way.
 *
 * <h2>Three of the five steps have no metric to hang off</h2>
 *
 * <p>SPEC 34.3's travel, visit and settle steps are not counters, so they are not modelled as
 * quests: they are events. Travel and visiting are watched here; settling is reported by the two
 * services that can settle a player, because neither a city founding nor a mining claim passes
 * through anything this class can see.
 */
public final class OnboardingListener implements Listener {

    private final Plugin plugin;
    private final OnboardingService onboarding;
    private final GuideBook guide;
    private final CityRegistry cities;
    private final ClaimRegistry claims;
    private final ConfigManager configs;
    private final LangManager lang;
    private final Messenger messenger;

    /** Which city a player was last seen in, so entering a new one is an event rather than a state. */
    private final java.util.Map<UUID, Integer> lastCity = new ConcurrentHashMap<>();

    public OnboardingListener(Plugin plugin, OnboardingService onboarding, GuideBook guide,
                              CityRegistry cities, ClaimRegistry claims, ConfigManager configs,
                              LangManager lang, Messenger messenger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.guide = Objects.requireNonNull(guide, "guide");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.messenger = Objects.requireNonNull(messenger, "messenger");
    }

    // ==================================================================================
    // SPEC 34.2's first session
    // ==================================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!player.hasPlayedBefore()) {
            firstSession(player);
            return;
        }
        // SPEC 34.4's contextual tip, one line, cycling. On a delay so it does not land in the
        // same tick as the login rewards and the city's own join messages.
        if (configs.get(ConfigFile.ONBOARDING).getBoolean("onboarding.tips-enabled", true)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    messenger.send(player.getUniqueId(), player, ToggleCategory.TIPS,
                            Channel.CHAT, tipKey(player));
                }
            }, 100L);
        }
    }

    /**
     * SPEC 34.2's seven steps, of which four land here.
     *
     * <p>The title first and alone — SPEC 34.2 asks for "One title only. No wall of chat text" —
     * then three chat lines staggered over the configured window so they are readable, then the
     * book in slot 8. The starting balance and the starter chain are the two services'.
     */
    private void firstSession(Player player) {
        var config = configs.get(ConfigFile.ONBOARDING);
        messenger.sendTitle(player.getUniqueId(), player, ToggleCategory.TIPS,
                "onboarding.welcome-title", "onboarding.welcome-subtitle");

        int stagger = Math.max(1, config.getInt("onboarding.message-stagger-seconds", 20));
        String[] lines = {"onboarding.intro-server", "onboarding.intro-guide",
                "onboarding.intro-land"};
        for (int i = 0; i < lines.length; i++) {
            String key = lines[i];
            // Spread across the window rather than all at once: three lines in one tick is the
            // wall of text SPEC 34.2 is explicitly avoiding.
            long delay = 40L + (long) i * stagger * 20L / lines.length;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    lang.send(player, key);
                }
            }, delay);
        }

        if (config.getBoolean("onboarding.give-guide-book", true)) {
            int slot = config.getInt("onboarding.guide-book-slot", 8);
            // Only if the slot is free. A book that displaced whatever a server's join kit put
            // there would be a nudge that took something away.
            if (player.getInventory().getItem(slot) == null) {
                player.getInventory().setItem(slot, guide.item());
            } else {
                player.getInventory().addItem(guide.item());
            }
        }
    }

    /**
     * SPEC 34.4's tips, as literals.
     *
     * <p>Written out rather than built from an index, for the reason {@code StarterStep} gives:
     * a concatenated key is invisible to the orphan sweep, so a seventh tip added to the language
     * files and forgotten here would sit there unread and nothing would say so.
     */
    private static final String[] TIPS = {
            "onboarding.tip-1", "onboarding.tip-2", "onboarding.tip-3",
            "onboarding.tip-4", "onboarding.tip-5", "onboarding.tip-6"};

    private String tipKey(Player player) {
        // Cycled by the player's own id and the day, so two players see different tips and one
        // player sees a new one tomorrow, with no state to keep.
        long day = System.currentTimeMillis() / (24L * 60 * 60 * 1000);
        return TIPS[Math.floorMod(player.getUniqueId().hashCode() + (int) day, TIPS.length)];
    }

    // ==================================================================================
    // SPEC 34.3's chain
    // ==================================================================================

    /** Step 2: "Use /rtp and travel 500 blocks from spawn." */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo().getBlockX() == event.getFrom().getBlockX()
                && event.getTo().getBlockZ() == event.getFrom().getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        Location spawn = player.getWorld().getSpawnLocation();
        if (spawn.getWorld() != player.getWorld()) {
            return;
        }
        double far = onboarding.travelBlocks();
        if (spawn.distanceSquared(event.getTo()) < far * far) {
            enteredCity(player, event.getTo());
            return;
        }
        award(player, StarterStep.TRAVEL);
        enteredCity(player, event.getTo());
    }

    /** Step 3: "Visit any existing city." Any city, including one they are not in. */
    private void enteredCity(Player player, Location to) {
        Optional<Claim> claim = claims.at(to.getWorld().getName(),
                to.getBlockX() >> 4, to.getBlockZ() >> 4);
        if (claim.isEmpty()) {
            lastCity.remove(player.getUniqueId());
            return;
        }
        Integer previous = lastCity.put(player.getUniqueId(), claim.get().cityId());
        if (previous != null && previous == claim.get().cityId()) {
            return;
        }
        cities.city(claim.get().cityId()).ifPresent(city -> award(player, StarterStep.VISIT_CITY));
    }

    /** Step 4: "Mine 32 iron ore in the resource world." */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Material type = event.getBlock().getType();
        if (type != Material.IRON_ORE && type != Material.DEEPSLATE_IRON_ORE) {
            return;
        }
        Player player = event.getPlayer();
        int mined = ironMined.merge(player.getUniqueId(), 1, Integer::sum);
        if (mined >= onboarding.ironTarget()) {
            award(player, StarterStep.MINE_IRON);
        }
    }

    /**
     * How much iron each player has broken this session.
     *
     * <p>In memory rather than a column. The step pays once and a player who logs out at 31 starts
     * again, which is the cost of not adding a counter to the schema for one 32-block errand —
     * and thirty-two ore is a few minutes, so the case is narrow.
     */
    private final java.util.Map<UUID, Integer> ironMined = new ConcurrentHashMap<>();

    /** Step 1, reported by the market: a sale is a service call, not an event. */
    public void onSold(UUID player) {
        Player online = plugin.getServer().getPlayer(player);
        if (online != null) {
            award(online, StarterStep.SELL_SOMETHING);
        }
    }

    /** Step 5, reported by the two services that can settle a player. */
    public void onSettled(UUID player) {
        Player online = plugin.getServer().getPlayer(player);
        if (online != null) {
            award(online, StarterStep.SETTLE);
        }
    }

    private void award(Player player, StarterStep step) {
        onboarding.completeQuietly(player.getUniqueId(), step, System.currentTimeMillis(),
                completion -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    messenger.send(player.getUniqueId(), player, ToggleCategory.QUESTS,
                            Channel.CHAT, step.messageKey(),
                            LangManager.placeholder("reward",
                                    completion.reward().toPlainString()));
                    if (completion.chainFinished()) {
                        messenger.send(player.getUniqueId(), player, ToggleCategory.QUESTS,
                                Channel.CHAT, "onboarding.chain-finished");
                    }
                }));
    }

    /** Forgets a leaver's session counters, so the maps stay proportional to who is online. */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        ironMined.remove(event.getPlayer().getUniqueId());
        lastCity.remove(event.getPlayer().getUniqueId());
    }
}
