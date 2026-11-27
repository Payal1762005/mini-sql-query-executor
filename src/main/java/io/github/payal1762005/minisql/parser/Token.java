package io.github.payal1762005.minisql.parser;

import java.util.Objects;

/**
 * A SQL token and its source location.
 *
 * @param lexeme exact source text of the token
 * @param value decoded value for string literals, otherwise the source lexeme
 */
public record Token(TokenType type, String lexeme, String value, int line, int column, int offset) {
    public Token {
        Objects.requireNonNull(type, "Token type must not be null");
        Objects.requireNonNull(lexeme, "Token lexeme must not be null");
        Objects.requireNonNull(value, "Token value must not be null");
        if (line < 1 || column < 1 || offset < 0) {
            throw new IllegalArgumentException("Token position must be non-negative with 1-based line and column");
        }
    }
}
