package io.github.payal1762005.minisql.exception;

/**
 * Raised when SQL text cannot be parsed.
 */
public class SqlParseException extends Exception {
    public SqlParseException(String message) {
        super(message);
    }

    public SqlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
