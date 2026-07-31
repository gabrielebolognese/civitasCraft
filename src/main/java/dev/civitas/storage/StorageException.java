package dev.civitas.storage;

import java.io.Serial;

/**
 * An unrecoverable storage fault: the database is unreachable, a migration failed, or a
 * query is malformed.
 *
 * <p>This is deliberately unchecked and deliberately not a {@code Result} failure. SPEC 2.3
 * reserves {@code Result} for <em>expected</em> outcomes such as "insufficient funds"; a
 * broken database is not an expected outcome and must not be quietly turned into a polite
 * message to the player.
 */
public class StorageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
