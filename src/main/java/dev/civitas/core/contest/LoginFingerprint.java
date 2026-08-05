package dev.civitas.core.contest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a connection address into something that can be compared but not read back.
 *
 * <h2>Why a hash and not the address</h2>
 * SPEC 13.4 needs one question answered: are these two accounts connecting from the same
 * place. SPEC 9.4.1's {@code /ca alts} will ask the same thing later. Neither needs the
 * address, and an address is the kind of thing that is fine to hold right up until the
 * database leaks. So the plugin stores {@code SHA-256(salt || address)} and never the address.
 *
 * <p>The salt is generated once, kept in a file beside the database rather than in it, and is
 * what makes the hash worth anything: without it, an attacker with the table could hash the
 * four billion IPv4 addresses in an afternoon and recover every one. With it, a stolen table
 * is a list of numbers.
 *
 * <p>Losing the salt file is survivable and self-correcting: every stored hash stops matching,
 * so the anti-abuse rule fails open (nothing is discarded) rather than wrongly discarding
 * everyone's votes, and hashes reform as players log in again.
 */
public final class LoginFingerprint {

    private static final String ALGORITHM = "SHA-256";
    private static final int SALT_BYTES = 32;

    private final byte[] salt;

    private LoginFingerprint(byte[] salt) {
        this.salt = salt.clone();
    }

    /** Loads the server's salt, creating one on first use. */
    public static LoginFingerprint load(File dataFolder) throws IOException {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Path file = dataFolder.toPath().resolve("login-salt.bin");
        if (Files.exists(file)) {
            byte[] existing = Files.readAllBytes(file);
            if (existing.length >= SALT_BYTES) {
                return new LoginFingerprint(existing);
            }
        }
        byte[] fresh = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(fresh);
        Files.createDirectories(file.getParent());
        Files.write(file, fresh);
        return new LoginFingerprint(fresh);
    }

    /** An in-memory instance with a fixed salt, for tests. */
    public static LoginFingerprint withSalt(byte[] salt) {
        return new LoginFingerprint(salt);
    }

    /**
     * @param address the connection address, or {@code null} for a player whose address is
     *                not available
     * @return the hash, or empty if there was no address to hash. Empty must never be treated
     *         as "matches nothing in particular": two players with no address are not two
     *         players on the same connection, and the callers check for empty rather than
     *         comparing empties.
     */
    public Optional<String> hash(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            digest.update(salt);
            digest.update(address.trim().getBytes(StandardCharsets.UTF_8));
            return Optional.of(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java implementation, so this cannot happen; if it
            // somehow does, the anti-abuse rule fails open rather than taking the server down.
            return Optional.empty();
        }
    }
}
