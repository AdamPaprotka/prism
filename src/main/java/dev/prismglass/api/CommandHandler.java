package dev.prismglass.api;

/** Runs a chat command; {@code args} excludes the command name. */
@FunctionalInterface
public interface CommandHandler {
    void run(String[] args) throws Exception;
}
