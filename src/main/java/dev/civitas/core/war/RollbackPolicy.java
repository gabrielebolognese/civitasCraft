package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;

/**
 * The SPEC 16.3 {@code rollback.*} switches that nothing read.
 *
 * <p>All five shipped in {@code war.yml} from M0, each with SPEC's own comment beside it, and
 * none of them was consulted anywhere: block drops were suppressed for any active war whatever
 * {@code suppress-block-drops} said, entities and container payloads were always restored, and
 * the two flags describing SPEC 11.7's loot rule described behaviour with no alternative
 * implementation. An operator could set any of them to false and watch nothing change.
 *
 * <h2>Two kinds of switch, and the difference matters</h2>
 *
 * <p>Three of them now do what they say. The other two are <b>declarations, not switches</b>:
 * {@code loot-is-permanent: false} would mean returning items a player carried out of a chest
 * during a war, and {@code vault-immune: false} would mean letting the vault be looted —
 * neither of which exists, and neither of which SPEC describes the behaviour of. Inventing
 * them to make a config key honest would be inventing a feature; silently ignoring them is
 * what this class was written to stop. So they are read, checked, and an operator who sets one
 * to an unsupported value is told at startup rather than discovering it after a war.
 *
 * <p>{@code suppress-block-drops} is honoured but warned about, because SPEC 11.8.3 calls the
 * no-drops rule "critical" and gives the reason: without it a war is a free strip-mining
 * event, since the attacker keeps 50,000 blocks of materials and the rollback puts the blocks
 * back anyway. Turning it off creates resources from nothing. That is the same class of
 * mistake as {@code rollback.enabled: false}, and gets the same loud treatment.
 */
public final class RollbackPolicy {

    private final ConfigManager configs;

    public RollbackPolicy(ConfigManager configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    private boolean flag(String key, boolean fallback) {
        return configs.get(ConfigFile.WAR).getBoolean("rollback." + key, fallback);
    }

    /** SPEC 11.8.3's no-drops rule. Honoured, and warned about when off. */
    public boolean suppressBlockDrops() {
        return flag("suppress-block-drops", true);
    }

    /** SPEC 11.8.3: villagers, animals and hangings are put back after the blocks are. */
    public boolean restoreEntities() {
        return flag("restore-entities", true);
    }

    /** SPEC 11.8.2 step 5: chest contents, sign text, banner patterns, spawner types. */
    public boolean restoreContainerNbt() {
        return flag("restore-container-nbt", true);
    }

    /** SPEC 11.7. A declaration: there is no implementation of returning looted items. */
    public boolean lootIsPermanent() {
        return flag("loot-is-permanent", true);
    }

    /** SPEC 11.7's mitigation. A declaration: nothing implements looting the vault. */
    public boolean vaultImmune() {
        return flag("vault-immune", true);
    }

    /**
     * Everything an operator has set that this build cannot honour, or should not.
     *
     * <p>Returned rather than logged, so the caller decides how loud to be and a test can read
     * the same list. Empty on a default configuration.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!suppressBlockDrops()) {
            problems.add("rollback.suppress-block-drops is false. Blocks broken in a war will "
                    + "drop their materials AND be restored by the rollback, which creates "
                    + "resources from nothing. SPEC 11.8.3 calls this rule critical.");
        }
        if (!lootIsPermanent()) {
            problems.add("rollback.loot-is-permanent is false, but returning items taken from "
                    + "a container during a war is not implemented. Loot stays permanent. "
                    + "See SPEC 11.7.");
        }
        if (!vaultImmune()) {
            problems.add("rollback.vault-immune is false, but looting the city vault is not "
                    + "implemented. The vault stays immune. See SPEC 11.7.");
        }
        return problems;
    }

    /** Logs {@link #problems}, loudly. Called once at startup beside the rollback warning. */
    public void warnAboutProblems(Logger logger) {
        for (String problem : problems()) {
            logger.severe(problem);
        }
    }
}
