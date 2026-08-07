package dev.civitas.core.claim;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/**
 * The ASCII chunk map, SPEC 6.5.
 *
 * <p>A 31 by 13 grid of chunks centred on the player, drawn with one coloured glyph per
 * chunk. Both the glyph and its colour come from {@code lang/}, so the map is translatable
 * and a server that prefers a different character can change it without a rebuild.
 *
 * <p>Dimensions are odd on both axes on purpose: an even grid has no centre chunk, and the
 * whole point is showing the player where they are standing.
 */
public final class ClaimMap {

    /** What a chunk is, from the viewer's point of view. */
    public enum Tile {
        /** The viewer's own city. */
        OWN("claim.map.tile.own"),
        /** A city allied to the viewer's. Reachable once M13 adds alliances. */
        ALLY("claim.map.tile.ally"),
        /** A city at war with the viewer's. Reachable once M19 adds wars. */
        ENEMY("claim.map.tile.enemy"),
        /** Any other city. */
        OTHER("claim.map.tile.other"),
        /** Unclaimed. */
        WILDERNESS("claim.map.tile.wilderness");

        private final String messageKey;

        Tile(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private final ClaimRegistry claims;
    private final CityRegistry cities;
    private final ConfigManager configs;
    private final LangManager lang;

    public ClaimMap(ClaimRegistry claims, CityRegistry cities, ConfigManager configs,
                    LangManager lang) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    /**
     * Renders the map as one line per row.
     *
     * @param viewerCity the viewer's city, or empty if they have none; decides which chunks
     *                   read as "own"
     */
    public List<Component> render(String world, int centreX, int centreZ, Optional<City> viewerCity) {
        int width = configs.get(ConfigFile.CITIES).getInt("claims.map-width", 31);
        int height = configs.get(ConfigFile.CITIES).getInt("claims.map-height", 13);

        int halfWidth = width / 2;
        int halfHeight = height / 2;

        List<Component> rows = new java.util.ArrayList<>(height);
        for (int dz = -halfHeight; dz <= halfHeight; dz++) {
            TextComponent.Builder row = Component.text();
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                int chunkX = centreX + dx;
                int chunkZ = centreZ + dz;
                boolean isCentre = dx == 0 && dz == 0;
                row.append(tileComponent(world, chunkX, chunkZ, viewerCity, isCentre));
            }
            rows.add(row.build());
        }
        return rows;
    }

    /** Classifies a chunk without rendering it, so the same rules drive the GUI mini-map. */
    public Tile tileAt(String world, int chunkX, int chunkZ, Optional<City> viewerCity) {
        Optional<Claim> claim = claims.at(world, chunkX, chunkZ);
        if (claim.isEmpty()) {
            return Tile.WILDERNESS;
        }
        int ownerId = claim.get().cityId();

        if (viewerCity.isPresent() && viewerCity.get().id() == ownerId) {
            return Tile.OWN;
        }
        if (viewerCity.isPresent()) {
            Optional<City> owner = cities.city(ownerId);
            if (owner.isPresent()) {
                // Diplomacy is M13 and wars are M19. Until then no city is an ally or an
                // enemy, so every other city reads as neutral; the branches exist so those
                // milestones have one place to fill in rather than a colour scheme to invent.
                if (isAlly(viewerCity.get(), owner.get())) {
                    return Tile.ALLY;
                }
                if (isAtWarWith(viewerCity.get(), owner.get())) {
                    return Tile.ENEMY;
                }
            }
        }
        return Tile.OTHER;
    }

    private Component tileComponent(String world, int chunkX, int chunkZ,
                                    Optional<City> viewerCity, boolean isCentre) {
        Tile tile = tileAt(world, chunkX, chunkZ, viewerCity);
        if (isCentre) {
            return lang.get("claim.map.tile.self");
        }
        return lang.get(tile.messageKey());
    }

    /** SPEC 6.5's yellow tile, wired by M13's diplomacy. */
    private boolean isAlly(City viewer, City other) {
        return diplomacy != null && diplomacy.areAllied(viewer.id(), other.id());
    }

    /** SPEC 6.5's red tile: a city on the other side of a live war. */
    private boolean isAtWarWith(City viewer, City other) {
        return wars != null && wars.engagedWarOf(viewer.id())
                .filter(war -> war.areEnemies(viewer.id(), other.id()))
                .isPresent();
    }

    private dev.civitas.core.diplomacy.DiplomacyRegistry diplomacy;
    private dev.civitas.core.war.WarRegistry wars;

    /** SPEC 6.5's ally colour, wired by M13. */
    public void useDiplomacy(dev.civitas.core.diplomacy.DiplomacyRegistry registry) {
        this.diplomacy = registry;
    }

    /** SPEC 6.5's enemy colour, wired by M19. */
    public void useWars(dev.civitas.core.war.WarRegistry registry) {
        this.wars = registry;
    }
}
