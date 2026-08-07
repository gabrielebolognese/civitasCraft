package dev.civitas.listener;

import java.util.Objects;

import dev.civitas.command.Replies;
import dev.civitas.core.admin.InspectMode;
import dev.civitas.lang.LangManager;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * SPEC 9.4.1's inspect mode: clicking a block reports who owns it.
 *
 * <h2>The click is consumed, not acted on</h2>
 * An admin in inspect mode who left-clicks a block is asking a question, not mining. Both the
 * interact and the break are cancelled while the mode is on, which is why the mode has to be
 * explicit and per-admin: an admin who forgot it was on and could not break anything would
 * conclude the plugin was broken, so the toggle message says clearly what it did.
 *
 * <p>At {@code LOW} priority so protection still has the final say. An admin inspecting is
 * asking about ownership, and the answer arriving before the protection listeners have run
 * costs nothing because the event is cancelled either way.
 */
public final class AdminInspectListener implements Listener {

    private final InspectMode inspect;
    private final LangManager lang;
    private final ProtectedChunkLookup protectedChunks;

    /** Whether a chunk is admin-protected, injected so this listener owns no state. */
    @FunctionalInterface
    public interface ProtectedChunkLookup {

        boolean isProtected(String world, int chunkX, int chunkZ);

        static ProtectedChunkLookup none() {
            return (world, chunkX, chunkZ) -> false;
        }
    }

    public AdminInspectListener(InspectMode inspect, LangManager lang,
                                ProtectedChunkLookup protectedChunks) {
        this.inspect = Objects.requireNonNull(inspect, "inspect");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.protectedChunks = Objects.requireNonNull(protectedChunks, "protectedChunks");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !inspect.isInspecting(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        report(event.getPlayer(), block);
    }

    /** A break in inspect mode is a question too, and must not remove the block. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!inspect.isInspecting(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        report(event.getPlayer(), event.getBlock());
    }

    /** Inspect mode is per-session, so a disconnect ends it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        inspect.forget(event.getPlayer().getUniqueId());
    }

    private void report(Player admin, Block block) {
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;
        InspectMode.Report report = inspect.describe(block.getLocation(),
                protectedChunks.isProtected(block.getWorld().getName(), chunkX, chunkZ));

        if (!report.claimed()) {
            lang.send(admin, report.adminProtected()
                            ? "admin.inspect.protected-wilderness"
                            : "admin.inspect.wilderness",
                    Replies.p("world", report.world()),
                    Replies.p("x", String.valueOf(report.chunkX())),
                    Replies.p("z", String.valueOf(report.chunkZ())));
            return;
        }

        lang.send(admin, "admin.inspect.claimed",
                Replies.p("city", report.city()),
                Replies.p("type", report.type()),
                Replies.p("world", report.world()),
                Replies.p("x", String.valueOf(report.chunkX())),
                Replies.p("z", String.valueOf(report.chunkZ())),
                Replies.p("claimed", stamp(report.claimedAt())),
                Replies.p("by", nameOf(report.claimedBy())),
                Replies.p("protected", report.adminProtected() ? "yes" : "no"));
    }

    private static String stamp(long millis) {
        return millis <= 0 ? "-" : java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochMilli(millis));
    }

    private static String nameOf(java.util.UUID uuid) {
        if (uuid == null) {
            return "-";
        }
        String name = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }
}
