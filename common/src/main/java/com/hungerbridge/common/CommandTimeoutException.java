package com.hungerbridge.common;

/** The server thread did not run a command in time (HTTP 504 {@code command_timeout}). */
public final class CommandTimeoutException extends Exception {

    public CommandTimeoutException(String command, long timeoutMs) {
        super("Command did not complete within " + timeoutMs + " ms: " + command);
    }
}
