package io.github.payal1762005.minisql.parser;

import io.github.payal1762005.minisql.exception.SqlParseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Converts SQL text into a position-aware sequence of tokens.
 */
public final class SqlTokenizer {
    private static final Map<String, TokenType> KEYWORDS = keywords();

    private String sql;
    private int index;
    private int line;
    private int column;
    private boolean previousWasCarriageReturn;

    public List<Token> tokenize(String sql) throws SqlParseException {
        this.sql = Objects.requireNonNull(sql, "SQL text must not be null");
        index = 0;
        line = 1;
        column = 1;
        previousWasCarriageReturn = false;

        List<Token> tokens = new ArrayList<>();
        while (!isAtEnd()) {
            if (Character.isWhitespace(peek())) {
                advance();
                continue;
            }
            tokens.add(nextToken());
        }
        tokens.add(new Token(TokenType.EOF, "", "", line, column, index));
        return List.copyOf(tokens);
    }

    private Token nextToken() throws SqlParseException {
        int start = index;
        int startLine = line;
        int startColumn = column;
        char current = peek();

        if (isIdentifierStart(current)) {
            advance();
            while (!isAtEnd() && isIdentifierPart(peek())) {
                advance();
            }
            String lexeme = sql.substring(start, index);
            TokenType type = KEYWORDS.getOrDefault(lexeme.toUpperCase(Locale.ROOT), TokenType.IDENTIFIER);
            return token(type, lexeme, lexeme, start, startLine, startColumn);
        }

        if (Character.isDigit(current)) {
            advance();
            while (!isAtEnd() && Character.isDigit(peek())) {
                advance();
            }
            if (!isAtEnd() && peek() == '.') {
                advance();
                if (isAtEnd() || !Character.isDigit(peek())) {
                    throw error("Invalid numeric literal; expected a digit after the decimal point",
                            startLine, startColumn, start);
                }
                while (!isAtEnd() && Character.isDigit(peek())) {
                    advance();
                }
            }
            String lexeme = sql.substring(start, index);
            return token(TokenType.NUMBER, lexeme, lexeme, start, startLine, startColumn);
        }

        if (current == '\'') {
            return stringToken(start, startLine, startColumn);
        }

        advance();
        return switch (current) {
            case ',' -> token(TokenType.COMMA, ",", ",", start, startLine, startColumn);
            case '(' -> token(TokenType.LEFT_PAREN, "(", "(", start, startLine, startColumn);
            case ')' -> token(TokenType.RIGHT_PAREN, ")", ")", start, startLine, startColumn);
            case '*' -> token(TokenType.ASTERISK, "*", "*", start, startLine, startColumn);
            case ';' -> token(TokenType.SEMICOLON, ";", ";", start, startLine, startColumn);
            case '=' -> token(TokenType.EQUALS, "=", "=", start, startLine, startColumn);
            case '!' -> {
                if (match('=')) {
                    yield token(TokenType.NOT_EQUALS, "!=", "!=", start, startLine, startColumn);
                }
                throw error("Unexpected character '!'; expected '=' after '!'", startLine, startColumn, start);
            }
            case '>' -> {
                if (match('=')) {
                    yield token(TokenType.GREATER_THAN_OR_EQUAL, ">=", ">=", start, startLine, startColumn);
                }
                yield token(TokenType.GREATER_THAN, ">", ">", start, startLine, startColumn);
            }
            case '<' -> {
                if (match('=')) {
                    yield token(TokenType.LESS_THAN_OR_EQUAL, "<=", "<=", start, startLine, startColumn);
                }
                if (match('>')) {
                    yield token(TokenType.NOT_EQUALS, "<>", "<>", start, startLine, startColumn);
                }
                yield token(TokenType.LESS_THAN, "<", "<", start, startLine, startColumn);
            }
            default -> throw error("Invalid character '" + current + "'", startLine, startColumn, start);
        };
    }

    private Token stringToken(int start, int startLine, int startColumn) throws SqlParseException {
        advance();
        StringBuilder value = new StringBuilder();
        while (!isAtEnd()) {
            if (peek() != '\'') {
                value.append(advance());
                continue;
            }

            advance();
            if (!isAtEnd() && peek() == '\'') {
                advance();
                value.append('\'');
                continue;
            }

            String lexeme = sql.substring(start, index);
            return token(TokenType.STRING, lexeme, value.toString(), start, startLine, startColumn);
        }
        throw error("Unterminated string literal", startLine, startColumn, start);
    }

    private Token token(TokenType type, String lexeme, String value,
                        int start, int startLine, int startColumn) {
        return new Token(type, lexeme, value, startLine, startColumn, start);
    }

    private SqlParseException error(String message, int errorLine, int errorColumn, int offset) {
        return new SqlParseException(message + " at line " + errorLine + ", column "
                + errorColumn + " (offset " + offset + ")");
    }

    private char advance() {
        char current = sql.charAt(index++);
        if (current == '\r') {
            line++;
            column = 1;
            previousWasCarriageReturn = true;
        } else if (current == '\n') {
            if (!previousWasCarriageReturn) {
                line++;
            }
            column = 1;
            previousWasCarriageReturn = false;
        } else {
            column++;
            previousWasCarriageReturn = false;
        }
        return current;
    }

    private boolean match(char expected) {
        if (isAtEnd() || peek() != expected) {
            return false;
        }
        advance();
        return true;
    }

    private char peek() {
        return sql.charAt(index);
    }

    private boolean isAtEnd() {
        return index >= sql.length();
    }

    private static boolean isIdentifierStart(char character) {
        return Character.isLetter(character) || character == '_';
    }

    private static boolean isIdentifierPart(char character) {
        return isIdentifierStart(character) || Character.isDigit(character);
    }

    private static Map<String, TokenType> keywords() {
        Map<String, TokenType> keywords = new HashMap<>();
        for (TokenType type : List.of(
                TokenType.SELECT, TokenType.EXPLAIN, TokenType.AS, TokenType.FROM, TokenType.WHERE, TokenType.GROUP,
                TokenType.BY, TokenType.AND, TokenType.OR, TokenType.COUNT, TokenType.SUM,
                TokenType.AVG, TokenType.MIN, TokenType.MAX)) {
            keywords.put(type.name(), type);
        }
        return Map.copyOf(keywords);
    }
}
