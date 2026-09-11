package io.cairn.store;

import io.cairn.core.Command;

/**
 * One command with the index it was assigned.
 *
 * @param index the 1-based log position
 * @param command what to apply there
 */
public record LogRecord(long index, Command command) {

    public LogRecord {
        if (index < 1) {
            throw new IllegalArgumentException("log indexes start at 1, got " + index);
        }
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
    }
}
