package net.kaloyan.smpmarket.storage;

/** Thrown when the market database cannot be opened, migrated, read or written. Unchecked. */
public final class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
