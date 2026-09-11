package io.cairn.store;

import io.cairn.core.Digest;

/** No artifact with that digest is in this blob store. */
public final class NoSuchBlobException extends StoreException {

    public NoSuchBlobException(Digest digest) {
        super("no artifact with digest " + digest);
    }
}
