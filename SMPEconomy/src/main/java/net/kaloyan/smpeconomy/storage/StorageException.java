package net.kaloyan.smpeconomy.storage;

/** The database could not be opened, migrated, read or written. Never swallowed silently. */
public final class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
