package dev.civitas.core.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The tile capture SPEC 11.8.1 asks for and paper-api cannot provide directly.
 *
 * <h2>What this can and cannot prove</h2>
 * SPEC 18.3's manual protocol is the real specification for this class: a chest with items, a
 * furnace mid-smelt, a sign with text, a banner with a pattern, a spawner. Some of those need
 * a real server to model, and MockBukkit does not implement every tile state, so the
 * round-trip tests below skip rather than lie where it cannot. What is always asserted is the
 * part that must hold everywhere: a payload that cannot be read, or that belongs to a
 * different block, must restore nothing rather than throw or corrupt.
 *
 * <p>The full round trip is confirmed by the SPEC 18.3 protocol at M20, on a real server. This
 * class exists so that most of it is already known to work before anyone runs that by hand.
 */
class TilePayloadCodecTest {

    private ServerMock server;
    private WorldMock world;
    private BukkitTilePayloadCodec codec;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        codec = new BukkitTilePayloadCodec(quiet());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Logger quiet() {
        Logger logger = Logger.getLogger("codec-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private Block blockOf(Material material, int x) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }

    // ==================================================================================
    // Classification
    // ==================================================================================

    @Test
    @DisplayName("a plain block carries no payload, so the common case costs nothing")
    void plainBlocksHaveNoPayload() {
        // Stone and dirt are the overwhelming majority of what an explosion logs. If they
        // produced payloads, every war would write hundreds of megabytes it did not need.
        assertFalse(codec.hasPayload(blockOf(Material.STONE, 0).getState()));
        assertFalse(codec.hasPayload(blockOf(Material.DIRT, 1).getState()));
        assertNull(codec.capture(blockOf(Material.STONE, 2).getState()));
    }

    @Test
    @DisplayName("a null state captures nothing rather than throwing")
    void nullStateIsSafe() {
        assertNull(codec.capture(null));
        assertFalse(codec.hasPayload(null));
    }

    // ==================================================================================
    // Defensive reading. These must hold on every server.
    // ==================================================================================

    @Test
    @DisplayName("an unreadable payload restores nothing instead of aborting the rollback")
    void garbagePayloadIsIgnored() {
        // A rollback that throws on one bad row leaves the rest of the city in ruins, so
        // every reader here fails soft.
        Block block = blockOf(Material.CHEST, 10);

        assertFalse(codec.restore(block, "not a yaml document: [[[".getBytes(StandardCharsets.UTF_8)));
        assertFalse(codec.restore(block, new byte[] {0, 1, 2, 3}));
        assertFalse(codec.restore(block, new byte[0]));
        assertFalse(codec.restore(block, null));
        assertFalse(codec.restore(null, "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a payload from a different block type is skipped, not forced on")
    void mismatchedPayloadIsSkipped() {
        // Replay runs in reverse, so a mismatch means an earlier entry has not landed yet.
        // Applying a chest's contents to a stone block would be worse than doing nothing.
        Block chest = blockOf(Material.CHEST, 11);
        byte[] payload = codec.capture(chest.getState());
        Assumptions.assumeTrue(payload != null, "MockBukkit does not model chest tile state");

        Block stone = blockOf(Material.STONE, 12);

        assertFalse(codec.restore(stone, payload));
    }

    @Test
    @DisplayName("what cannot be captured is stated, not hidden")
    void limitationsAreDeclared() {
        // SPEC 11.8.1 asks for full NBT through an API that does not exist. A codec that
        // silently dropped what it cannot read would turn SPEC 18.3 step 8 into a test that
        // passes while being wrong.
        assertFalse(codec.knownLimitations().isEmpty());
        assertTrue(codec.knownLimitations().stream()
                        .anyMatch(line -> line.toLowerCase(java.util.Locale.ROOT).contains("bee")),
                "the beehive gap is the one a player would notice, and must be declared");
    }

    // ==================================================================================
    // Round trips, where the harness can model them
    // ==================================================================================

    @Test
    @DisplayName("a chest's contents survive capture and restore")
    void chestRoundTrip() {
        Block block = blockOf(Material.CHEST, 20);
        Assumptions.assumeTrue(block.getState() instanceof Container,
                "MockBukkit does not model chest tile state on this version");

        Container chest = (Container) block.getState();
        chest.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 12));
        chest.getInventory().setItem(5, new ItemStack(Material.OAK_LOG, 3));
        chest.update(true, false);

        Assumptions.assumeTrue(
                ((Container) block.getState()).getSnapshotInventory().getItem(0) != null,
                "MockBukkit does not persist chest contents through a BlockState; the full "
                        + "round trip is covered by the SPEC 18.3 protocol on a real server");

        byte[] payload = codec.capture(block.getState());
        assertNotNull(payload, "a chest must produce a payload");

        // Empty it, exactly as looting or breaking would.
        Container emptied = (Container) block.getState();
        emptied.getInventory().clear();
        emptied.update(true, false);

        assertTrue(codec.restore(block, payload));

        Container restored = (Container) block.getState();
        assertEquals(Material.DIAMOND, restored.getInventory().getItem(0).getType());
        assertEquals(12, restored.getInventory().getItem(0).getAmount());
        assertEquals(Material.OAK_LOG, restored.getInventory().getItem(5).getType());
    }

    @Test
    @DisplayName("a sign's text survives capture and restore")
    void signRoundTrip() {
        Block block = blockOf(Material.OAK_SIGN, 21);
        Assumptions.assumeTrue(block.getState() instanceof Sign,
                "MockBukkit does not model sign tile state on this version");

        Sign sign = (Sign) block.getState();
        sign.getSide(org.bukkit.block.sign.Side.FRONT)
                .line(0, net.kyori.adventure.text.Component.text("Roma"));
        sign.update(true, false);

        byte[] payload = codec.capture(block.getState());
        assertNotNull(payload);

        Sign cleared = (Sign) block.getState();
        cleared.getSide(org.bukkit.block.sign.Side.FRONT)
                .line(0, net.kyori.adventure.text.Component.empty());
        cleared.update(true, false);

        assertTrue(codec.restore(block, payload));

        Sign restored = (Sign) block.getState();
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText()
                .serialize(restored.getSide(org.bukkit.block.sign.Side.FRONT).line(0));
        assertEquals("Roma", text);
    }
}
