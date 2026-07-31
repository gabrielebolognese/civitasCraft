package dev.civitas.command;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Turns a {@link Result} into a message for the player who asked for it.
 *
 * <p>Failures carry a message key and placeholders, never text (SPEC 2.1), so the whole
 * translation happens here rather than at every call site.
 */
public final class Replies {

    private Replies() {
    }

    /** Sends a failure's message, with its placeholders. */
    public static void sendFailure(Audience audience, LangManager lang,
                                   Result.Failure<?> failure) {
        lang.send(audience, failure.messageKey(),
                LangManager.placeholders(failure.placeholders()));
    }

    /**
     * Waits for an async service call, then replies on the server thread.
     *
     * <p>A failure sends its own message; a success is handed to {@code onSuccess}. An
     * unexpected exception is logged with its stack trace and reported to the player as a
     * generic error, because the details of a database fault are not a player's problem and
     * silently doing nothing would look like the command was ignored.
     */
    public static <T> void reply(CompletableFuture<Result<T>> future, Audience audience,
                                 LangManager lang, Scheduler scheduler, Logger logger,
                                 Consumer<T> onSuccess) {
        future.whenComplete((result, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Command failed unexpectedly", error);
                lang.send(audience, "command.error");
                return;
            }
            if (result instanceof Result.Failure<T> failure) {
                sendFailure(audience, lang, failure);
                return;
            }
            onSuccess.accept(result.orElseThrow());
        }));
    }

    /** Convenience for the common "one placeholder" message. */
    public static TagResolver p(String name, String value) {
        return LangManager.placeholder(name, value);
    }
}
