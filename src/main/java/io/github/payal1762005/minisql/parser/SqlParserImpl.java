package io.github.payal1762005.minisql.parser;

import io.github.payal1762005.minisql.exception.SqlParseException;
import io.github.payal1762005.minisql.model.AllColumns;
import io.github.payal1762005.minisql.model.AggregateExpression;
import io.github.payal1762005.minisql.model.ColumnReference;
import io.github.payal1762005.minisql.model.ComparisonExpression;
import io.github.payal1762005.minisql.model.LiteralValue;
import io.github.payal1762005.minisql.model.LogicalExpression;
import io.github.payal1762005.minisql.model.LogicalOperator;
import io.github.payal1762005.minisql.model.OrderByItem;
import io.github.payal1762005.minisql.model.OrderDirection;
import io.github.payal1762005.minisql.model.QueryPlan;
import io.github.payal1762005.minisql.model.SelectItem;
import io.github.payal1762005.minisql.model.SelectQuery;
import io.github.payal1762005.minisql.model.WhereExpression;
import io.github.payal1762005.minisql.operators.AggregateFunction;
import io.github.payal1762005.minisql.operators.ComparisonOperator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses the supported SELECT query subset into an immutable AST.
 */
public final class SqlParserImpl implements SqlParser {
    private static final Map<TokenType, ComparisonOperator> COMPARISON_OPERATORS = Map.of(
            TokenType.EQUALS, ComparisonOperator.EQUALS,
            TokenType.NOT_EQUALS, ComparisonOperator.NOT_EQUALS,
            TokenType.GREATER_THAN, ComparisonOperator.GREATER_THAN,
            TokenType.LESS_THAN, ComparisonOperator.LESS_THAN,
            TokenType.GREATER_THAN_OR_EQUAL, ComparisonOperator.GREATER_THAN_OR_EQUAL,
            TokenType.LESS_THAN_OR_EQUAL, ComparisonOperator.LESS_THAN_OR_EQUAL);

    @Override
    public SelectQuery parse(String sql) throws SqlParseException {
        return parse(new SqlTokenizer().tokenize(sql));
    }

    /**
     * Parses a sequence produced by {@link SqlTokenizer}.
     */
    public SelectQuery parse(List<Token> tokens) throws SqlParseException {
        Objects.requireNonNull(tokens, "Tokens must not be null");
        if (tokens.isEmpty() || tokens.get(tokens.size() - 1).type() != TokenType.EOF) {
            throw new SqlParseException("Token sequence must end with EOF");
        }
        for (int index = 0; index < tokens.size() - 1; index++) {
            Token token = tokens.get(index);
            if (token.type() == TokenType.EOF) {
                throw new SqlParseException("Unexpected token after EOF at line " + token.line()
                        + ", column " + token.column());
            }
        }
        return new Parser(tokens).parseQuery();
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int current;

        private Parser(List<Token> tokens) {
            this.tokens = List.copyOf(tokens);
        }

        private SelectQuery parseQuery() throws SqlParseException {
            boolean explain = match(TokenType.EXPLAIN);
            consume(TokenType.SELECT, "Expected SELECT at the start of the query");
            List<SelectItem> selectItems = parseSelectItems();
            consume(TokenType.FROM, "Expected FROM after the SELECT list");
            String tableName = consumeIdentifier("Expected a table name after FROM").value();

            Optional<WhereExpression> where = Optional.empty();
            if (match(TokenType.WHERE)) {
                where = Optional.of(parseOrExpression());
            }

            List<String> groupByColumns = List.of();
            if (match(TokenType.GROUP)) {
                consume(TokenType.BY, "Expected BY after GROUP");
                groupByColumns = parseColumnList("GROUP BY");
            }

            List<OrderByItem> orderByItems = List.of();
            if (match(TokenType.ORDER)) {
                consume(TokenType.BY, "Expected BY after ORDER");
                orderByItems = parseOrderByItems();
            }

            Optional<Integer> limit = Optional.empty();
            if (match(TokenType.LIMIT)) {
                limit = Optional.of(parseLimit());
            }

            match(TokenType.SEMICOLON);
            consume(TokenType.EOF, "Unexpected token after the query");
            return new SelectQuery(
                    selectItems, tableName, where, groupByColumns, explain, orderByItems, limit);
        }

        private List<SelectItem> parseSelectItems() throws SqlParseException {
            List<SelectItem> items = new ArrayList<>();
            items.add(parseSelectItem());
            while (match(TokenType.COMMA)) {
                items.add(parseSelectItem());
            }
            return List.copyOf(items);
        }

        private SelectItem parseSelectItem() throws SqlParseException {
            SelectItem item;
            if (match(TokenType.ASTERISK)) {
                item = new AllColumns();
            } else if (isIdentifier(peek().type())) {
                advance();
                item = new ColumnReference(previous().value());
            } else {
                AggregateFunction function = aggregateFunction(peek().type());
                if (function == null) {
                    throw error(peek(), "Expected a column or aggregate expression or '*' in the SELECT list");
                }
                advance();
                consume(TokenType.LEFT_PAREN, "Expected '(' after aggregate function");

                String column;
                if (match(TokenType.ASTERISK)) {
                    if (function != AggregateFunction.COUNT) {
                        throw error(previous(), "Only COUNT accepts '*'");
                    }
                    column = "*";
                } else {
                    column = consumeIdentifier(
                            "Expected a column name as the aggregate argument").value();
                }
                consume(TokenType.RIGHT_PAREN, "Expected ')' after aggregate argument");
                item = new AggregateExpression(function, column);
            }

            if (!match(TokenType.AS)) {
                return item;
            }
            Token alias = consumeIdentifier("Expected an identifier after AS");
            if (item instanceof ColumnReference column) {
                return new ColumnReference(column.columnName(), alias.value());
            }
            if (item instanceof AggregateExpression aggregate) {
                return new AggregateExpression(aggregate.function(), aggregate.columnName(), alias.value());
            }
            throw error(alias, "SELECT * cannot have an alias");
        }

        private List<String> parseColumnList(String clause) throws SqlParseException {
            List<String> columns = new ArrayList<>();
            columns.add(consumeIdentifier("Expected a column after " + clause).value());
            while (match(TokenType.COMMA)) {
                columns.add(consumeIdentifier("Expected a column after ',' in " + clause).value());
            }
            return List.copyOf(columns);
        }

        private List<OrderByItem> parseOrderByItems() throws SqlParseException {
            List<OrderByItem> items = new ArrayList<>();
            items.add(parseOrderByItem());
            while (match(TokenType.COMMA)) {
                items.add(parseOrderByItem());
            }
            return List.copyOf(items);
        }

        private OrderByItem parseOrderByItem() throws SqlParseException {
            Token column = consumeIdentifier("Expected a column name after ORDER BY");
            OrderDirection direction = OrderDirection.ASC;
            if (match(TokenType.ASC)) {
                direction = OrderDirection.ASC;
            } else if (match(TokenType.DESC)) {
                direction = OrderDirection.DESC;
            } else if (peek().type() == TokenType.IDENTIFIER) {
                throw error(peek(), "Invalid ORDER BY direction '" + peek().value()
                        + "'; expected ASC or DESC");
            }
            return new OrderByItem(column.value(), direction);
        }

        private int parseLimit() throws SqlParseException {
            if (match(TokenType.MINUS)) {
                Token number = peek();
                if (match(TokenType.NUMBER)) {
                    throw error(number, "LIMIT must be non-negative");
                }
                throw error(number, "Expected a non-negative integer after LIMIT");
            }

            Token token = consume(TokenType.NUMBER, "Expected a non-negative integer after LIMIT");
            if (token.value().contains(".")) {
                throw error(token, "LIMIT must be an integer");
            }
            try {
                BigInteger value = new BigInteger(token.value());
                if (value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                    throw error(token, "LIMIT exceeds the maximum supported value "
                            + Integer.MAX_VALUE);
                }
                return value.intValueExact();
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new SqlParseException("Invalid LIMIT value at line " + token.line()
                        + ", column " + token.column(), exception);
            }
        }

        private WhereExpression parseOrExpression() throws SqlParseException {
            WhereExpression expression = parseAndExpression();
            while (match(TokenType.OR)) {
                WhereExpression right = parseAndExpression();
                expression = new LogicalExpression(LogicalOperator.OR, expression, right);
            }
            return expression;
        }

        private WhereExpression parseAndExpression() throws SqlParseException {
            WhereExpression expression = parsePrimaryExpression();
            while (match(TokenType.AND)) {
                WhereExpression right = parsePrimaryExpression();
                expression = new LogicalExpression(LogicalOperator.AND, expression, right);
            }
            return expression;
        }

        private WhereExpression parsePrimaryExpression() throws SqlParseException {
            if (match(TokenType.LEFT_PAREN)) {
                WhereExpression expression = parseOrExpression();
                consume(TokenType.RIGHT_PAREN, "Expected ')' after WHERE expression");
                return expression;
            }
            return parseComparison();
        }

        private ComparisonExpression parseComparison() throws SqlParseException {
            String column = consumeIdentifier(
                    "Expected a column name in WHERE condition").value();
            Token operatorToken = peek();
            ComparisonOperator operator = COMPARISON_OPERATORS.get(operatorToken.type());
            if (operator == null) {
                throw error(operatorToken, "Expected a comparison operator after column '" + column + "'");
            }
            advance();
            return new ComparisonExpression(column, operator, parseLiteral());
        }

        private LiteralValue parseLiteral() throws SqlParseException {
            Token token = peek();
            try {
                return switch (token.type()) {
                    case STRING -> {
                        advance();
                        yield new LiteralValue(LiteralValue.Type.STRING, token.value());
                    }
                    case NUMBER -> {
                        advance();
                        if (token.value().contains(".")) {
                            yield new LiteralValue(LiteralValue.Type.DECIMAL,
                                    new BigDecimal(token.value()));
                        }
                        yield new LiteralValue(LiteralValue.Type.INTEGER,
                                new BigInteger(token.value()));
                    }
                    default -> throw error(token, "Expected a string, integer, or decimal literal");
                };
            } catch (NumberFormatException exception) {
                throw new SqlParseException("Invalid numeric literal at line " + token.line()
                        + ", column " + token.column(), exception);
            }
        }

        private Token consume(TokenType type, String message) throws SqlParseException {
            if (check(type)) {
                return advance();
            }
            throw error(peek(), message);
        }

        private Token consumeIdentifier(String message) throws SqlParseException {
            if (isIdentifier(peek().type())) {
                return advance();
            }
            throw error(peek(), message);
        }

        private static boolean isIdentifier(TokenType type) {
            return type == TokenType.IDENTIFIER || type == TokenType.ORDER
                    || type == TokenType.ASC || type == TokenType.DESC || type == TokenType.LIMIT;
        }

        private boolean match(TokenType type) {
            if (check(type)) {
                advance();
                return true;
            }
            return false;
        }

        private boolean check(TokenType type) {
            return peek().type() == type;
        }

        private Token advance() {
            if (!check(TokenType.EOF)) {
                current++;
            }
            return previous();
        }

        private Token peek() {
            return tokens.get(current);
        }

        private Token previous() {
            return tokens.get(current - 1);
        }

        private SqlParseException error(Token token, String message) {
            return new SqlParseException(message + " at line " + token.line()
                    + ", column " + token.column());
        }

        private static AggregateFunction aggregateFunction(TokenType type) {
            return switch (type) {
                case COUNT -> AggregateFunction.COUNT;
                case SUM -> AggregateFunction.SUM;
                case AVG -> AggregateFunction.AVG;
                case MIN -> AggregateFunction.MIN;
                case MAX -> AggregateFunction.MAX;
                default -> null;
            };
        }
    }
}
