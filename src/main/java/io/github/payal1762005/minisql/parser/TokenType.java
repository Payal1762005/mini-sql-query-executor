package io.github.payal1762005.minisql.parser;

/**
 * Token kinds recognized by the SQL tokenizer.
 */
public enum TokenType {
    SELECT,
    EXPLAIN,
    AS,
    FROM,
    WHERE,
    GROUP,
    BY,
    ORDER,
    ASC,
    DESC,
    LIMIT,
    AND,
    OR,
    COUNT,
    SUM,
    AVG,
    MIN,
    MAX,
    IDENTIFIER,
    NUMBER,
    STRING,
    COMMA,
    LEFT_PAREN,
    RIGHT_PAREN,
    ASTERISK,
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    LESS_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN_OR_EQUAL,
    SEMICOLON,
    MINUS,
    EOF
}
