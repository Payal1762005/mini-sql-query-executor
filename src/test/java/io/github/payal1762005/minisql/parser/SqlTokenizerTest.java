package io.github.payal1762005.minisql.parser;

import io.github.payal1762005.minisql.exception.SqlParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTokenizerTest {
    private final SqlTokenizer tokenizer = new SqlTokenizer();

    @Test
    void tokenizesBasicSelectStatement() throws SqlParseException {
        List<Token> tokens = tokenizer.tokenize("SELECT id, name FROM employees;");

        assertEquals(List.of(
                TokenType.SELECT, TokenType.IDENTIFIER, TokenType.COMMA, TokenType.IDENTIFIER,
                TokenType.FROM, TokenType.IDENTIFIER, TokenType.SEMICOLON, TokenType.EOF),
                types(tokens));
        assertEquals("id", tokens.get(1).value());
        assertEquals("employees", tokens.get(5).value());
    }

    @Test
    void recognizesKeywordsWithoutChangingIdentifierValues() throws SqlParseException {
        List<Token> tokens = tokenizer.tokenize(
                "select explain as from where group by and or count sum avg min max SelectName");

        assertEquals(List.of(
                TokenType.SELECT, TokenType.EXPLAIN, TokenType.AS, TokenType.FROM, TokenType.WHERE,
                TokenType.GROUP, TokenType.BY,
                TokenType.AND, TokenType.OR, TokenType.COUNT, TokenType.SUM, TokenType.AVG,
                TokenType.MIN, TokenType.MAX, TokenType.IDENTIFIER, TokenType.EOF), types(tokens));
        assertEquals("SelectName", tokens.get(14).value());
    }

    @Test
    void tracksPositionsAcrossWhitespaceAndNewlines() throws SqlParseException {
        List<Token> tokens = tokenizer.tokenize("SELECT\r\n  name\nFROM people");

        assertEquals(1, tokens.get(0).line());
        assertEquals(1, tokens.get(0).column());
        assertEquals(2, tokens.get(1).line());
        assertEquals(3, tokens.get(1).column());
        assertEquals(3, tokens.get(2).line());
        assertEquals(1, tokens.get(2).column());
    }

    @Test
    void tokenizesWhereBooleanOperatorsAndComparisonOperators() throws SqlParseException {
        List<Token> tokens = tokenizer.tokenize(
                "WHERE a = 1 AND b != 2 OR c <> 3 AND d > 4 AND e < 5 AND f >= 6 AND g <= 7");

        assertEquals(List.of(
                TokenType.WHERE, TokenType.IDENTIFIER, TokenType.EQUALS, TokenType.NUMBER,
                TokenType.AND, TokenType.IDENTIFIER, TokenType.NOT_EQUALS, TokenType.NUMBER,
                TokenType.OR, TokenType.IDENTIFIER, TokenType.NOT_EQUALS, TokenType.NUMBER,
                TokenType.AND, TokenType.IDENTIFIER, TokenType.GREATER_THAN, TokenType.NUMBER,
                TokenType.AND, TokenType.IDENTIFIER, TokenType.LESS_THAN, TokenType.NUMBER,
                TokenType.AND, TokenType.IDENTIFIER, TokenType.GREATER_THAN_OR_EQUAL, TokenType.NUMBER,
                TokenType.AND, TokenType.IDENTIFIER, TokenType.LESS_THAN_OR_EQUAL, TokenType.NUMBER,
                TokenType.EOF), types(tokens));
    }

    @Test
    void tokenizesIntegerAndDecimalLiterals() throws SqlParseException {
        List<Token> tokens = tokenizer.tokenize("0 42 3.14 0.125");

        assertEquals(List.of("0", "42", "3.14", "0.125"),
                tokens.subList(0, 4).stream().map(Token::value).toList());
        assertEquals(List.of(TokenType.NUMBER, TokenType.NUMBER, TokenType.NUMBER, TokenType.NUMBER,
                TokenType.EOF), types(tokens));
    }

    @Test
    void tokenizesStringLiteralsAndPreservesEscapedQuotes() throws SqlParseException {
        List<Token> tokens = tokenizer.tokenize("'hello world' 'It''s fine'");

        assertEquals(List.of(TokenType.STRING, TokenType.STRING, TokenType.EOF), types(tokens));
        assertEquals("'hello world'", tokens.get(0).lexeme());
        assertEquals("hello world", tokens.get(0).value());
        assertEquals("'It''s fine'", tokens.get(1).lexeme());
        assertEquals("It's fine", tokens.get(1).value());
    }

    @Test
    void tokenizesParenthesesCommasAsteriskAndSemicolon() throws SqlParseException {
        List<Token> tokens = tokenizer.tokenize("COUNT(*) (a, b);");

        assertEquals(List.of(TokenType.COUNT, TokenType.LEFT_PAREN, TokenType.ASTERISK,
                TokenType.RIGHT_PAREN, TokenType.LEFT_PAREN, TokenType.IDENTIFIER, TokenType.COMMA,
                TokenType.IDENTIFIER, TokenType.RIGHT_PAREN, TokenType.SEMICOLON, TokenType.EOF),
                types(tokens));
    }

    @Test
    void reportsUnexpectedCharactersWithTheirPosition() {
        SqlParseException exception = assertThrows(SqlParseException.class,
                () -> tokenizer.tokenize("SELECT @"));

        assertTrue(exception.getMessage().contains("Invalid character '@'"));
        assertTrue(exception.getMessage().contains("line 1, column 8"));
    }

    @Test
    void reportsUnterminatedStringWithItsStartingPosition() {
        SqlParseException exception = assertThrows(SqlParseException.class,
                () -> tokenizer.tokenize("SELECT 'unfinished"));

        assertTrue(exception.getMessage().contains("Unterminated string literal"));
        assertTrue(exception.getMessage().contains("line 1, column 8"));
    }

    @Test
    void rejectsMalformedDecimalLiterals() {
        SqlParseException exception = assertThrows(SqlParseException.class,
                () -> tokenizer.tokenize("12."));

        assertTrue(exception.getMessage().contains("Invalid numeric literal"));
        assertTrue(exception.getMessage().contains("line 1, column 1"));
    }

    private List<TokenType> types(List<Token> tokens) {
        return tokens.stream().map(Token::type).toList();
    }
}
