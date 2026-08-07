package dev.civitas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SPEC 9 and 10: the command tree is declared once and its permission nodes really exist. */
class CommandRegistryTest {

    /**
     * plugin.yml is a template ({@code ${version}}) expanded at build time, but the
     * permissions block below is plain YAML and parses as-is.
     */
    private static FileConfiguration pluginYml() {
        File onDisk = new File("src/main/resources/plugin.yml");
        assertTrue(onDisk.isFile(), "plugin.yml is missing");
        return YamlConfiguration.loadConfiguration(onDisk);
    }

    /**
     * Bukkit's YamlConfiguration treats '.' as a path separator, so the literal key
     * {@code civitas.admin.war} loads as three nested sections. Paper parses plugin.yml's
     * permission block from the raw YAML map instead, where the key stays literal, so the
     * nesting is an artefact of reading it this way. Flatten it back: a node is any path
     * that carries a {@code default}, which every permission declaration must.
     */
    private static Set<String> declaredPermissionNodes() {
        ConfigurationSection permissions = pluginYml().getConfigurationSection("permissions");
        assertTrue(permissions != null, "plugin.yml declares no permissions");

        Set<String> nodes = new TreeSet<>();
        for (String path : permissions.getKeys(true)) {
            if (permissions.isConfigurationSection(path) && permissions.contains(path + ".default")) {
                nodes.add(path);
            }
        }
        return nodes;
    }

    private static Object declaredDefault(String node) {
        ConfigurationSection permissions = pluginYml().getConfigurationSection("permissions");
        assertTrue(permissions != null);
        return permissions.get(node + ".default");
    }

    @Test
    @DisplayName("no command is left stubbed")
    void nothingIsStillAStub() {
        // Empty since M23: every command in SPEC 9 has a real implementation. The stub
        // mechanism is kept because it is how this tree was built one milestone at a time,
        // and a future command wants the same treatment — but a stub reaching a release is a
        // command that answers "not implemented yet" to a player, so it should be a decision
        // somebody made rather than a leftover nobody noticed.
        assertTrue(CommandRegistry.declaredCommands().isEmpty(),
                "these commands are still stubs: " + CommandRegistry.declaredCommands().stream()
                        .map(CommandSpec::name).toList());
    }

    @Test
    @DisplayName("no root command or alias is declared twice")
    void namesAndAliasesAreUnique() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new TreeSet<>();

        for (CommandSpec spec : CommandRegistry.declaredCommands()) {
            if (!seen.add(spec.name())) {
                duplicates.add(spec.name());
            }
            for (String alias : spec.aliases()) {
                if (!seen.add(alias)) {
                    duplicates.add(alias);
                }
            }
        }

        assertTrue(duplicates.isEmpty(), "duplicate command literals: " + duplicates);
    }

    @Test
    @DisplayName("every command gates on a permission node declared in plugin.yml")
    void permissionsAreDeclared() {
        Set<String> declared = declaredPermissionNodes();
        Set<String> undeclared = new TreeSet<>();

        for (CommandSpec spec : CommandRegistry.declaredCommands()) {
            if (!declared.contains(spec.permission())) {
                undeclared.add(spec.name() + " -> " + spec.permission());
            }
        }

        assertTrue(undeclared.isEmpty(), "commands gated on undeclared permissions: " + undeclared);
    }

    @Test
    @DisplayName("plugin.yml declares every permission node in SPEC Section 10")
    void specPermissionNodesArePresent() {
        List<String> required = List.of(
                "civitas.use",
                "civitas.city.create",
                "civitas.economy.balance",
                "civitas.economy.pay",
                "civitas.market.use",
                "civitas.quests.use",
                "civitas.contest.use",
                "civitas.bounty.use",
                "civitas.bypass.claim",
                "civitas.bypass.cooldown",
                "civitas.bypass.war",
                "civitas.bypass.economy",
                "civitas.admin",
                "civitas.admin.info",
                "civitas.admin.audit",
                "civitas.admin.inspect",
                "civitas.admin.city",
                "civitas.admin.claim",
                "civitas.admin.economy",
                "civitas.admin.war",
                "civitas.admin.event",
                "civitas.admin.contest",
                "civitas.admin.system",
                "civitas.admin.*");

        Set<String> declared = declaredPermissionNodes();
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(declared);

        assertTrue(missing.isEmpty(), "plugin.yml is missing SPEC 10 permission nodes: " + missing);
    }

    @Test
    @DisplayName("plugin.yml keeps the SPEC 10 defaults, so admin nodes are never public")
    void permissionDefaults() {
        assertTrue(Boolean.TRUE.equals(declaredDefault("civitas.use")),
                "civitas.use defaults to true in SPEC 10");

        for (String node : declaredPermissionNodes()) {
            if (node.startsWith("civitas.admin") || node.startsWith("civitas.bypass")) {
                assertEquals("op", declaredDefault(node),
                        node + " must default to op, never true");
            }
        }
    }

    @Test
    @DisplayName("commands the specification does not gate on op are available to players")
    void playerCommandsAreNotOpOnly() {
        for (CommandSpec spec : CommandRegistry.declaredCommands()) {
            if (spec.name().equals("cityadmin")) {
                continue;
            }
            assertFalse(spec.permission().startsWith("civitas.admin"),
                    spec.name() + " is a player command and must not require an admin node");
        }
    }
}
