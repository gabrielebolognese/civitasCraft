package dev.civitas.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityMember;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.war.War;

/**
 * SPEC 36.4's API over the live services.
 *
 * <p>Every method goes through the same registries the plugin's own commands use, so a third-party
 * caller cannot see a world the server does not. Nothing here mutates: see {@link CivitasApi}.
 *
 * <p>Built with a {@code Supplier} rather than the services themselves, for the reason CLAUDE.md
 * gives about the null window — the database opens asynchronously, and an API registered at enable
 * would otherwise hand out a null the first plugin to call it would throw on.
 */
public final class CivitasApiImpl implements CivitasApi {

    private final Supplier<CivitasServices> services;

    public CivitasApiImpl(Supplier<CivitasServices> services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override
    public boolean isReady() {
        return services.get() != null;
    }

    @Override
    public Optional<CityView> city(int cityId) {
        return ready().flatMap(current -> current.registry().city(cityId)).map(Wrapped::new);
    }

    @Override
    public Optional<CityView> cityByName(String name) {
        return ready().flatMap(current -> current.registry().cityByName(name)).map(Wrapped::new);
    }

    @Override
    public Optional<CityView> cityOf(UUID player) {
        return ready().flatMap(current -> current.registry().cityOf(player)).map(Wrapped::new);
    }

    @Override
    public Collection<CityView> cities() {
        return ready().map(current -> current.registry().cities().stream()
                        .map(city -> (CityView) new Wrapped(city)).toList())
                .orElseGet(List::of);
    }

    @Override
    public Optional<CityView> claimAt(String world, int chunkX, int chunkZ) {
        return ready().flatMap(current -> current.claimRegistry().at(world, chunkX, chunkZ)
                .flatMap(claim -> current.registry().city(claim.cityId())))
                .map(Wrapped::new);
    }

    @Override
    public boolean canBuild(UUID player, String world, int chunkX, int chunkZ) {
        // Asked of ProtectionService rather than reassembled from the claim, so a caller gets the
        // same answer the block listeners give — including bypass, trust and dormancy.
        return ready().map(current -> !current.protection()
                        .check(player, false, world, chunkX, chunkZ,
                                dev.civitas.core.protection.ProtectionAction.BUILD)
                        .denied())
                .orElse(false);
    }

    @Override
    public BigDecimal balance(UUID player) {
        return ready().map(current -> current.economy().balanceOrZero(player))
                .orElse(BigDecimal.ZERO);
    }

    @Override
    public Optional<WarView> warOf(int cityId) {
        return ready().flatMap(current -> current.wars().registry().engagedWarOf(cityId))
                .map(WrappedWar::new);
    }

    private Optional<CivitasServices> ready() {
        return Optional.ofNullable(services.get());
    }

    // ==================================================================================

    /** A city, narrowed to what SPEC 36.4 exposes. */
    private final class Wrapped implements CityView {

        private final City city;

        private Wrapped(City city) {
            this.city = city;
        }

        @Override
        public int id() {
            return city.id();
        }

        @Override
        public String name() {
            return city.name();
        }

        @Override
        public String tag() {
            return city.tag();
        }

        @Override
        public UUID mayor() {
            return city.mayorUuid();
        }

        @Override
        public BigDecimal treasury() {
            return city.treasury();
        }

        @Override
        public long foundedAt() {
            return city.foundedAt();
        }

        @Override
        public Collection<UUID> members() {
            return city.members().stream().map(CityMember::uuid).toList();
        }

        @Override
        public Optional<String> rankOf(UUID player) {
            return city.rankOf(player).map(CityRank::name);
        }

        @Override
        public boolean hasPermission(UUID player, String flag) {
            // By name rather than by the enum, so a caller need not compile against this
            // plugin's internals to ask. An unknown flag is false, not an exception: a
            // third-party plugin should not be able to crash on a typo in its own config.
            try {
                return city.hasPermission(player, CityPermission.valueOf(flag));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        @Override
        public int claimCount() {
            return ready().map(current -> current.claimRegistry().claimsOf(city.id()).size())
                    .orElse(0);
        }

        @Override
        public boolean isAtWar() {
            return warOf(city.id()).isPresent();
        }
    }

    /** A war, narrowed to what SPEC 36.4 exposes. */
    private record WrappedWar(War war) implements WarView {

        @Override
        public int id() {
            return war.id();
        }

        @Override
        public int attackerCityId() {
            return war.attackerCityId();
        }

        @Override
        public int defenderCityId() {
            return war.defenderCityId();
        }

        @Override
        public String state() {
            return war.state().key();
        }

        @Override
        public long endsAt() {
            return war.warEndsAt();
        }

        @Override
        public boolean zoneContains(String world, int chunkX, int chunkZ) {
            return war.zone() != null && war.zone().containsChunk(world, chunkX, chunkZ);
        }
    }
}
