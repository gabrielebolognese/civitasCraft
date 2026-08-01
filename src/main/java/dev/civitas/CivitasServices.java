package dev.civitas;

import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.city.CityService;
import dev.civitas.core.city.RankService;
import dev.civitas.core.claim.BorderRenderer;
import dev.civitas.core.claim.ClaimMap;
import dev.civitas.core.claim.ClaimRegistry;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.economy.PlayerAccountService;
import dev.civitas.core.protection.BlockClassifier;
import dev.civitas.core.protection.ProtectionGuard;
import dev.civitas.core.protection.ProtectionService;
import dev.civitas.util.PlayerLookup;

/**
 * The services that exist only once storage is open.
 *
 * <p>Commands are registered while the server is starting, but the database opens on an
 * async task, so a command can be typed before its service exists. Handing commands this
 * object through a supplier rather than the services directly makes that window explicit:
 * the supplier returns null until everything is ready, and a command that finds null says
 * so instead of throwing.
 */
public record CivitasServices(
        CityRegistry registry,
        CityService cities,
        RankService ranks,
        ClaimRegistry claimRegistry,
        ClaimService claims,
        ClaimMap map,
        BorderRenderer borders,
        ProtectionService protection,
        ProtectionGuard guard,
        BlockClassifier blockClassifier,
        PlayerAccountService accounts,
        PlayerLookup lookup) {
}
