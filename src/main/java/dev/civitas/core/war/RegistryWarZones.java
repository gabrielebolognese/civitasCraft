package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The real {@link WarZones}, backed by the war registry.
 *
 * <p>This one class is what turns M17 and M18 from tested machinery into a live promise.
 * Until it is wired in, {@link WarZones#none()} answers "no war" and the block logger records
 * nothing; with it, every block changed inside an active war's zone becomes a row that M18 can
 * put back.
 *
 * <p>Answers from memory only. It is consulted on every block change of every player in a war,
 * which SPEC 2.1 forbids from touching storage and which SPEC 11.4 anticipates by asking for a
 * precomputed set.
 */
public final class RegistryWarZones implements WarZones {

    private final WarRegistry registry;

    public RegistryWarZones(WarRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public List<Integer> warsCovering(String world, int x, int y, int z) {
        List<War> covering = registry.activeWarsCovering(world, x, z);
        if (covering.isEmpty()) {
            return List.of();
        }
        // SPEC 17.4 case 51: a chunk inside two zones logs to both, or the war that missed it
        // would roll back to a state the other war's damage had already left behind.
        List<Integer> ids = new ArrayList<>(covering.size());
        for (War war : covering) {
            ids.add(war.id());
        }
        return ids;
    }

    @Override
    public boolean isAnyWarActive() {
        return registry.isAnyWarActive();
    }
}
