package dev.civitas.core.market.craft;

import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

/**
 * Walks the server's recipe list into a {@link RecipeGraph}, SPEC 21.10.2.
 *
 * <p>The other half of the graph. {@link CraftingEdges} writes down what the specification
 * names; this picks up everything else the server actually has, including recipes from other
 * plugins and datapacks — which matter, because a datapack that adds a reversible recipe
 * between two listed items re-opens SPEC 21.3's loop just as surely as a vanilla one.
 *
 * <h2>Only recipes whose inputs are a single material</h2>
 *
 * <p>A recipe taking several distinct materials is not a conversion in the sense SPEC 21.3
 * cares about. Crafting a bucket takes three iron ingots, so iron ingot to bucket is a real
 * edge; crafting a piston takes planks, cobblestone, iron and redstone, and reading that as
 * "redstone converts into pistons" would relate almost every material to almost every other
 * and refuse a buy list of any size.
 *
 * <p>The arbitrage SPEC describes needs a player to convert a quantity of one traded item
 * into a quantity of another and back. That requires the recipe's inputs to be substantially
 * one thing. Multi-input recipes consume something the market does not price, which is what
 * breaks the loop.
 *
 * <p>Isolated behind this class so the graph itself stays testable without a server.
 */
public final class BukkitRecipeSource {

    private BukkitRecipeSource() {
    }

    /**
     * Adds every single-input recipe the server knows to {@code graph}.
     *
     * @return how many edges were added, for the startup log
     */
    public static int addAllTo(RecipeGraph graph, Logger logger) {
        int added = 0;
        try {
            Iterator<Recipe> recipes = Bukkit.recipeIterator();
            while (recipes.hasNext()) {
                added += addOne(graph, recipes.next());
            }
        } catch (RuntimeException e) {
            // A server that cannot list its recipes is not a reason to start an unguarded
            // market, but it is also not this class's decision — MarketSafetyCheck still runs
            // against the hardcoded tables, which cover everything SPEC 21.3 names.
            logger.log(Level.WARNING, "Could not read the server recipe list; the crafting "
                    + "equivalence graph is built from the hardcoded tables only.", e);
        }
        return added;
    }

    private static int addOne(RecipeGraph graph, Recipe recipe) {
        ItemStack result = recipe.getResult();
        if (result == null || result.getType().isAir()) {
            return 0;
        }
        String output = result.getType().name();

        if (recipe instanceof CookingRecipe<?> cooking) {
            return addFrom(graph, cooking.getInputChoice(), output);
        }
        if (recipe instanceof StonecuttingRecipe cutting) {
            return addFrom(graph, cutting.getInputChoice(), output);
        }
        if (recipe instanceof ShapedRecipe shaped) {
            return addIfSingleInput(graph, shaped.getChoiceMap().values(), output);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            return addIfSingleInput(graph, shapeless.getChoiceList(), output);
        }
        // Smithing, merchant and other recipe types: not a material-to-material conversion a
        // player can run in bulk without a second priced input, so not an arbitrage edge.
        return 0;
    }

    /**
     * Adds edges only when every slot of the recipe wants the same material.
     *
     * <p>Nine iron ingots to an iron block qualifies. Planks plus cobblestone plus redstone
     * does not, for the reason in the class javadoc.
     */
    private static int addIfSingleInput(RecipeGraph graph,
                                        java.util.Collection<org.bukkit.inventory.RecipeChoice>
                                                choices, String output) {
        java.util.Set<String> materials = new java.util.LinkedHashSet<>();
        for (org.bukkit.inventory.RecipeChoice choice : choices) {
            if (choice == null) {
                continue;   // an empty slot in a shaped recipe
            }
            materials.addAll(materialsOf(choice));
        }
        if (materials.size() != 1) {
            return 0;
        }
        graph.addEdge(materials.iterator().next(), output);
        return 1;
    }

    private static int addFrom(RecipeGraph graph,
                               org.bukkit.inventory.RecipeChoice choice, String output) {
        int added = 0;
        for (String material : materialsOf(choice)) {
            graph.addEdge(material, output);
            added++;
        }
        return added;
    }

    /**
     * The materials a choice accepts.
     *
     * <p>A tag choice — "any log", "any plank" — accepts many, and every one of them is a real
     * conversion into the output, so all of them become edges.
     */
    private static java.util.Set<String> materialsOf(
            org.bukkit.inventory.RecipeChoice choice) {
        java.util.Set<String> materials = new java.util.LinkedHashSet<>();
        if (choice instanceof org.bukkit.inventory.RecipeChoice.MaterialChoice material) {
            material.getChoices().forEach(type -> materials.add(type.name()));
        } else if (choice instanceof org.bukkit.inventory.RecipeChoice.ExactChoice exact) {
            exact.getChoices().forEach(stack -> materials.add(stack.getType().name()));
        }
        return materials;
    }
}
