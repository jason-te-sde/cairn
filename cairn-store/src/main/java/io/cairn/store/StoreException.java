package io.cairn.store;

/**
 * Durable state is damaged or unreachable.
 *
 * <p>Distinct from {@link io.cairn.codec.CodecException} — which says a particular record is not
 * decodable — because the recoveries differ. A single unreadable record at the end of the log is
 * an unsynced tail and is truncated; an unreadable record in the middle, or a segment whose first
 * index does not follow the previous segment's last, means the store is not what it claims to be
 * and starting up anyway would mean serving a registry with a hole in it.
 */
public class StoreException extends RuntimeException {

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
