package dev.civitas.core.market;

import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.util.Result;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * What the server market will and will not buy, SPEC 17.3 cases 29 and 30.
 *
 * <p>Only plain, vanilla, undamaged, unenchanted, unnamed items are accepted. The reason is
 * pricing: the market values a material, not an item, so it has no way to price a Mending
 * pickaxe or a shulker box holding someone's diamonds, and would happily pay iron-ingot
 * money for either. Refusing them is the only correct answer available.
 *
 * <p>Each rule is a config toggle so an operator can loosen one without a rebuild, but the
 * defaults are all on.
 */
public final class MarketItemFilter {

    private final ConfigManager configs;

    public MarketItemFilter(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * Whether the market may accept this stack.
     *
     * @return success, or a failure naming which rule refused it
     */
    public Result<ItemStack> accept(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return Result.failure("EMPTY_HAND", "market.empty-hand");
        }
        if (!stack.getType().isItem()) {
            return Result.failure("NOT_AN_ITEM", "market.not-traded");
        }

        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta == null) {
            return Result.success(stack);
        }

        if (rejectDamaged() && meta instanceof Damageable damageable && damageable.hasDamage()) {
            return Result.failure("DAMAGED", "market.rejected-damaged");
        }
        if (rejectEnchanted() && (meta.hasEnchants() || hasStoredEnchants(meta))) {
            return Result.failure("ENCHANTED", "market.rejected-enchanted");
        }
        if (rejectNamed() && (meta.hasDisplayName() || meta.hasLore())) {
            return Result.failure("NAMED", "market.rejected-named");
        }
        if (rejectFullContainers() && holdsItems(meta)) {
            return Result.failure("HAS_CONTENTS", "market.rejected-contents");
        }
        return Result.success(stack);
    }

    /** SPEC 17.3 case 30: a container with anything inside it is never accepted. */
    private static boolean holdsItems(ItemMeta meta) {
        if (meta instanceof BlockStateMeta blockState && blockState.hasBlockState()
                && blockState.getBlockState() instanceof Container container) {
            for (ItemStack content : container.getInventory().getContents()) {
                if (content != null && !content.getType().isAir()) {
                    return true;
                }
            }
        }
        return meta instanceof BundleMeta bundle && bundle.hasItems();
    }

    private static boolean hasStoredEnchants(ItemMeta meta) {
        return meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta stored
                && stored.hasStoredEnchants();
    }

    private boolean rejectDamaged() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("market.reject-damaged", true);
    }

    private boolean rejectEnchanted() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("market.reject-enchanted", true);
    }

    private boolean rejectNamed() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("market.reject-named", true);
    }

    private boolean rejectFullContainers() {
        return configs.get(ConfigFile.ECONOMY)
                .getBoolean("market.reject-containers-with-contents", true);
    }
}
