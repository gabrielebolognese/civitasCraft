package dev.civitas.core.vault;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.upgrade.UpgradeType;
import dev.civitas.storage.dao.CityVaultDao;
import dev.civitas.storage.row.CityVaultRow;
import dev.civitas.util.Result;
import org.bukkit.inventory.ItemStack;

/**
 * The shared city vault, SPEC 5.7 and 9.2.
 *
 * <h2>Why it exists</h2>
 * SPEC 11.7 is the answer. War looting is permanent by design, which would be intolerable if
 * there were nowhere safe: "anything a city cannot afford to lose goes in the vault before
 * the war". That turns protecting your valuables into a pre-war decision rather than a
 * disaster, and it is the reason the vault is worth an upgrade track of its own.
 *
 * <h2>Serialisation</h2>
 * A page is stored through Paper's own {@code ItemStack.serializeItemsAsBytes}, which is the
 * format Minecraft itself keeps working across versions. Hand-rolling a column layout would
 * be smaller and would break the first time an item gained a component the layout did not
 * know about, which for a store of a city's valuables is not a trade worth making.
 */
public final class VaultService {

    /** SPEC 5.7: a page is 27 slots. */
    public static final int DEFAULT_PAGE_SIZE = 27;

    private final CityVaultDao vaults;
    private final UpgradeService upgrades;
    private final ConfigManager configs;

    public VaultService(CityVaultDao vaults, UpgradeService upgrades, ConfigManager configs) {
        this.vaults = Objects.requireNonNull(vaults, "vaults");
        this.upgrades = Objects.requireNonNull(upgrades, "upgrades");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    // ==================================================================================
    // How much vault a city has
    // ==================================================================================

    /**
     * How many pages this city has unlocked.
     *
     * <p>SPEC 5.7 grants one per Vault level and lists no base, so a city that has bought
     * nothing has no vault at all. That is the conservative reading and it is what makes the
     * first level of the track worth 30,000 C.
     */
    public int pagesOf(City city) {
        int perLevel = (int) Math.max(1,
                Math.round(upgrades.effectPerLevel(UpgradeType.VAULT, 1)));
        return upgrades.levelOf(city, UpgradeType.VAULT) * perLevel;
    }

    public int pageSize() {
        return configs.get(ConfigFile.CITIES)
                .getInt(UpgradeType.VAULT.configPath() + ".page-size", DEFAULT_PAGE_SIZE);
    }

    // ==================================================================================
    // Opening
    // ==================================================================================

    /**
     * Checks whether this player may open this page.
     *
     * <p>SPEC 9.2 gates the vault on {@code CONTAINER}, which is the same permission that
     * governs a chest inside a claim: a member trusted with the city's chests is trusted with
     * the city's vault.
     *
     * @param page zero-based
     */
    public Result<Integer> checkAccess(UUID actor, City city, int page) {
        if (!city.isMember(actor)) {
            return Result.failure("NOT_A_MEMBER", "city.not-a-member");
        }
        if (!city.hasPermission(actor, CityPermission.CONTAINER)) {
            return Result.failure("NO_CITY_PERMISSION", "city.no-permission",
                    Map.of("permission", CityPermission.CONTAINER.name()));
        }
        int pages = pagesOf(city);
        if (pages <= 0) {
            return Result.failure("NO_VAULT", "vault.none");
        }
        if (page < 0 || page >= pages) {
            return Result.failure("NO_SUCH_PAGE", "vault.no-page",
                    Map.of("pages", String.valueOf(pages)));
        }
        return Result.success(page);
    }

    /** Reads a page's contents. An unwritten page comes back as an empty array. */
    public CompletableFuture<ItemStack[]> load(int cityId, int page) {
        return vaults.find(cityId, page).thenApply(found -> found
                .filter(row -> !row.isEmpty())
                .map(row -> deserialise(row.contents()))
                .orElseGet(() -> new ItemStack[pageSize()]));
    }

    /** Writes a page back. */
    public CompletableFuture<Integer> save(int cityId, int page, ItemStack[] contents) {
        byte[] blob = serialise(contents);
        return vaults.save(new CityVaultRow(cityId, page, blob, System.currentTimeMillis()));
    }

    /** Everything a city has stored, for an admin dump and for disband cleanup. */
    public CompletableFuture<java.util.List<CityVaultRow>> pagesStored(int cityId) {
        return vaults.findByCity(cityId);
    }

    // ==================================================================================
    // Serialisation
    // ==================================================================================

    /**
     * Turns a page into bytes.
     *
     * <p>A failure here would silently eat a city's valuables, so it throws rather than
     * returning null: losing the write and knowing about it is recoverable, losing it
     * quietly is not.
     */
    public byte[] serialise(ItemStack[] contents) {
        try {
            // Nulls are empty slots; the format keeps them, so slot positions survive.
            return ItemStack.serializeItemsAsBytes(contents);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not serialise a vault page", e);
        }
    }

    /**
     * Reads a page back.
     *
     * <p>An unreadable page returns empty rather than throwing, and that is deliberate: a
     * page the server cannot parse is already lost, and refusing to open the vault at all
     * would take the other pages with it.
     */
    public ItemStack[] deserialise(byte[] blob) {
        try {
            ItemStack[] read = ItemStack.deserializeItemsFromBytes(blob);

            // A page saved when the configured size was smaller still opens, at the size it
            // is now, rather than throwing away what is in it.
            ItemStack[] page = new ItemStack[Math.max(read.length, pageSize())];
            for (int slot = 0; slot < read.length; slot++) {
                // The format round-trips an empty slot as AIR rather than as null. Both are
                // empty to an inventory, but not to a caller counting free slots with a null
                // check, and a freshly created page uses nulls. One shape, either way.
                page[slot] = read[slot] == null || read[slot].getType().isAir()
                        ? null
                        : read[slot];
            }
            return page;
        } catch (RuntimeException e) {
            return new ItemStack[pageSize()];
        }
    }

    public Optional<CityVaultDao> dao() {
        return Optional.of(vaults);
    }
}
