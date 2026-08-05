package dev.civitas.core.diplomacy;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityMember;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.income.StipendTask;
import dev.civitas.lang.LangManager;

/**
 * The sweep that makes time pass in diplomacy, SPEC 14.2 and 14.3.
 *
 * <p>Two jobs. A break whose 24-hour notice has run out becomes a real break, and an expired
 * truce stops counting. Both are things that must happen without anybody being online: a city
 * that gives notice and then nobody logs in for two days should still find the alliance ended.
 */
public final class DiplomacyTask implements Runnable {

    private final DiplomacyService diplomacy;
    private final DiplomacyRegistry registry;
    private final CityRegistry cities;
    private final StipendTask.Notifier notifier;
    private final Logger logger;

    public DiplomacyTask(DiplomacyService diplomacy, DiplomacyRegistry registry,
                         CityRegistry cities, StipendTask.Notifier notifier, Logger logger) {
        this.diplomacy = Objects.requireNonNull(diplomacy, "diplomacy");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void run() {
        try {
            sweep(System.currentTimeMillis());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "The diplomacy sweep failed", e);
        }
    }

    /**
     * Completes anything whose time has come.
     *
     * @return how many alliances ended
     */
    public int sweep(long now) {
        int ended = 0;
        for (Alliance alliance : registry.all()) {
            if (!diplomacy.noticeExpired(alliance, now)) {
                continue;
            }
            diplomacy.completeBreak(alliance, now);
            announce(alliance);
            ended++;
        }

        // Expired truces are dropped from the cache but left in the table. Every read
        // already compares against the clock, so this frees memory rather than changing an
        // answer, and an admin reading the table can still see that the pact existed.
        registry.forgetExpiredTruces(now);
        return ended;
    }

    /** Both cities are told, because both are affected and only one of them chose it. */
    private void announce(Alliance alliance) {
        tell(alliance.cityAId(), alliance.cityBId());
        tell(alliance.cityBId(), alliance.cityAId());
    }

    private void tell(int cityId, int otherCityId) {
        City city = cities.city(cityId).orElse(null);
        String otherName = cities.city(otherCityId).map(City::name).orElse("?");
        if (city == null) {
            return;
        }
        for (CityMember member : city.members()) {
            notifier.tell(member.uuid(), "diplomacy.alliance-ended",
                    LangManager.placeholder("city", otherName));
        }
    }
}
