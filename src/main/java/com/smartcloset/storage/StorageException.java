package com.smartcloset.storage;

import java.io.IOException;

/** The bucket refused or could not be reached; the API answers 503 instead of a generic processing error. */
public class StorageException extends IOException {
    public StorageException(String message) { super(message); }
    public StorageException(String message, Throwable cause) { super(message, cause); }
}
