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
import io.github.payal1762005.minisql.model.SelectQuery;
import io.github.payal1762005.minisql.model.WhereExpression;
import io.github.payal1762005.minisql.operators.AggregateFunction;
import io.github.payal1762005.minisql.operators.ComparisonOperator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParserImplTest {
    private final SqlParserImpl parser = new SqlParserImpl();

    @Test
    void parsesSimpleSelect() throws SqlParseException {
        SelectQuery query = parse("SELECT id FROM people");

        assertEquals(List.of(new ColumnReference("id")), query.selectItems());
        assertEquals("people", query.tableName());
        assertEquals(Optional.empty(), query.where());
        assertEquals(List.of(), query.groupByColumns());
    }

    @Test
    void parsesExplainPrefixWithoutChangingQueryFields() throws SqlParseException {
        SelectQuery query = parse("EXPLAIN SELECT name FROM employees WHERE salary > 50000;");

        assertTrue(query.explain());
        assertEquals("employees", query.tableName());
        assertEquals(List.of(new ColumnReference("name")), query.selectItems());
    }

    @Test
    void ordinarySelectIsNotMarkedAsExplain() throws SqlParseException {
        assertFalse(parse("SELECT name FROM employees").explain());
    }

    @Test
    void parsesMultipleSelectedColumnsInOrder() throws SqlParseException {
        SelectQuery query = parse("SELECT id, name, department FROM employees");

        assertEquals(List.of(new ColumnReference("id"), new ColumnReference("name"),
                new ColumnReference("department")), query.selectItems());
    }

    @Test
    void parsesWildcardAndColumnAliases() throws SqlParseException {
        SelectQuery wildcard = parse("SELECT * FROM employees");
        SelectQuery aliases = parse("SELECT name AS employee_name, salary AS monthly_salary FROM employees");

        assertEquals(List.of(new AllColumns()), wildcard.selectItems());
        assertEquals(List.of(new ColumnReference("name", "employee_name"),
                new ColumnReference("salary", "monthly_salary")), aliases.selectItems());
    }

    @Test
    void parsesAggregateAliases() throws SqlParseException {
        SelectQuery query = parse("SELECT COUNT(*) AS employee_count, SUM(salary) AS payroll FROM employees");

        assertEquals(List.of(new AggregateExpression(AggregateFunction.COUNT, "*", "employee_count"),
                new AggregateExpression(AggregateFunction.SUM, "salary", "payroll")),
                query.selectItems());
    }

    @Test
    void rejectsMalformedAliasSyntax() {
        assertParseError("SELECT name AS FROM employees", "Expected an identifier after AS",
                "line 1, column 16");
        assertParseError("SELECT * AS all_columns FROM employees", "SELECT * cannot have an alias",
                "line 1, column 13");
        assertParseError("SELECT name employee_name FROM employees", "Expected FROM",
                "line 1, column 13");
    }

    @Test
    void parsesAllAggregateFunctionsAndCountStar() throws SqlParseException {
        SelectQuery query = parse(
                "SELECT COUNT(*), COUNT(id), SUM(amount), AVG(amount), MIN(age), MAX(age) FROM data");

        assertEquals(List.of(
                new AggregateExpression(AggregateFunction.COUNT, "*"),
                new AggregateExpression(AggregateFunction.COUNT, "id"),
                new AggregateExpression(AggregateFunction.SUM, "amount"),
                new AggregateExpression(AggregateFunction.AVG, "amount"),
                new AggregateExpression(AggregateFunction.MIN, "age"),
                new AggregateExpression(AggregateFunction.MAX, "age")), query.selectItems());
    }

    @Test
    void parsesAllComparisonOperators() throws SqlParseException {
        List<ComparisonOperator> expected = List.of(
                ComparisonOperator.EQUALS, ComparisonOperator.NOT_EQUALS,
                ComparisonOperator.NOT_EQUALS, ComparisonOperator.GREATER_THAN,
                ComparisonOperator.LESS_THAN, ComparisonOperator.GREATER_THAN_OR_EQUAL,
                ComparisonOperator.LESS_THAN_OR_EQUAL);
        List<String> sqlOperators = List.of("=", "!=", "<>", ">", "<", ">=", "<=");

        for (int index = 0; index < sqlOperators.size(); index++) {
            ComparisonExpression comparison = assertInstanceOf(ComparisonExpression.class,
                    parse("SELECT id FROM t WHERE id " + sqlOperators.get(index) + " 1")
                            .where().orElseThrow());
            assertEquals(expected.get(index), comparison.operator());
        }
    }

    @Test
    void parsesStringIntegerAndDecimalLiterals() throws SqlParseException {
        ComparisonExpression string = whereComparison("name = 'O''Brien'");
        ComparisonExpression integer = whereComparison("age = 42");
        ComparisonExpression decimal = whereComparison("price >= 19.95");

        assertEquals(new LiteralValue(LiteralValue.Type.STRING, "O'Brien"), string.value());
        assertEquals(new LiteralValue(LiteralValue.Type.INTEGER, BigInteger.valueOf(42)), integer.value());
        assertEquals(new LiteralValue(LiteralValue.Type.DECIMAL, new BigDecimal("19.95")), decimal.value());
    }

    @Test
    void andBindsMoreTightlyThanOr() throws SqlParseException {
        WhereExpression expression = parse(
                "SELECT id FROM t WHERE a = 1 OR b = 2 AND c = 3").where().orElseThrow();

        LogicalExpression root = assertInstanceOf(LogicalExpression.class, expression);
        assertEquals(LogicalOperator.OR, root.operator());
        assertInstanceOf(ComparisonExpression.class, root.left());
        LogicalExpression and = assertInstanceOf(LogicalExpression.class, root.right());
        assertEquals(LogicalOperator.AND, and.operator());
        assertInstanceOf(ComparisonExpression.class, and.left());
        assertInstanceOf(ComparisonExpression.class, and.right());
    }

    @Test
    void parenthesesOverrideLogicalPrecedence() throws SqlParseException {
        WhereExpression expression = parse(
                "SELECT id FROM t WHERE (a = 1 OR b = 2) AND c = 3").where().orElseThrow();

        LogicalExpression root = assertInstanceOf(LogicalExpression.class, expression);
        assertEquals(LogicalOperator.AND, root.operator());
        LogicalExpression grouped = assertInstanceOf(LogicalExpression.class, root.left());
        assertEquals(LogicalOperator.OR, grouped.operator());
        assertInstanceOf(ComparisonExpression.class, root.right());
    }

    @Test
    void parsesGroupByWithMultipleColumns() throws SqlParseException {
        SelectQuery query = parse("SELECT department, role FROM staff GROUP BY department, role");

        assertEquals(List.of("department", "role"), query.groupByColumns());
    }

    @Test
    void parsesOrderByAscendingByDefaultAndExplicitDirections() throws SqlParseException {
        SelectQuery defaultAscending = parse("SELECT name FROM employees ORDER BY name");
        SelectQuery explicitAscending = parse("SELECT name FROM employees ORDER BY name ASC");
        SelectQuery descending = parse("SELECT name FROM employees ORDER BY salary DESC");

        assertEquals(List.of(new OrderByItem("name", OrderDirection.ASC)),
                defaultAscending.orderByItems());
        assertEquals(defaultAscending.orderByItems(), explicitAscending.orderByItems());
        assertEquals(List.of(new OrderByItem("salary", OrderDirection.DESC)),
                descending.orderByItems());
    }

    @Test
    void parsesMultipleOrderKeysWithWhereAndGroupBy() throws SqlParseException {
        SelectQuery query = parse("SELECT department, AVG(salary) AS avg_salary FROM employees "
                + "WHERE salary > 10 GROUP BY department "
                + "ORDER BY department ASC, avg_salary DESC");

        assertTrue(query.where().isPresent());
        assertEquals(List.of("department"), query.groupByColumns());
        assertEquals(List.of(new OrderByItem("department", OrderDirection.ASC),
                new OrderByItem("avg_salary", OrderDirection.DESC)), query.orderByItems());
    }

    @Test
    void parsesLimitAloneCombinedWithOrderAndZero() throws SqlParseException {
        SelectQuery limited = parse("SELECT name FROM employees LIMIT 10;");
        SelectQuery orderedLimited = parse(
                "SELECT name FROM employees ORDER BY salary DESC LIMIT 5;");
        SelectQuery zero = parse("SELECT name FROM employees LIMIT 0");

        assertEquals(java.util.Optional.of(10), limited.limit());
        assertEquals(List.of(), limited.orderByItems());
        assertEquals(java.util.Optional.of(5), orderedLimited.limit());
        assertEquals(List.of(new OrderByItem("salary", OrderDirection.DESC)),
                orderedLimited.orderByItems());
        assertEquals(java.util.Optional.of(0), zero.limit());
    }

    @Test
    void parsesNewKeywordsCaseInsensitivelyAndAllowsThemAsIdentifiers() throws SqlParseException {
        SelectQuery query = parse(
                "sElEcT ORDER, Asc, DESC, LIMIT fRoM LIMIT oRdEr bY ASC dEsC lImIt 1");

        assertEquals(List.of(new ColumnReference("ORDER"), new ColumnReference("Asc"),
                new ColumnReference("DESC"), new ColumnReference("LIMIT")), query.selectItems());
        assertEquals("LIMIT", query.tableName());
        assertEquals(List.of(new OrderByItem("ASC", OrderDirection.DESC)), query.orderByItems());
        assertEquals(java.util.Optional.of(1), query.limit());
    }

    @Test
    void rejectsMalformedOrderByClauses() {
        assertParseError("SELECT name FROM employees ORDER BY",
                "Expected a column name after ORDER BY", "line 1, column 36");
        assertParseError("SELECT name FROM employees ORDER BY name,",
                "Expected a column name after ORDER BY", "line 1, column 42");
        assertParseError("SELECT name FROM employees ORDER BY name SIDEWAYS",
                "Invalid ORDER BY direction 'SIDEWAYS'; expected ASC or DESC",
                "line 1, column 42");
        assertParseError("SELECT name FROM employees ORDER BY name ASC DESC",
                "Unexpected token after the query", "line 1, column 46");
        assertParseError("SELECT name FROM employees GROUP BY name ORDER BY name WHERE name = 'x'",
                "Unexpected token after the query", "line 1, column 56");
    }

    @Test
    void rejectsInvalidLimitValuesWithClearErrors() {
        assertParseError("SELECT name FROM employees LIMIT",
                "Expected a non-negative integer after LIMIT", "line 1, column 33");
        assertParseError("SELECT name FROM employees LIMIT 1.5",
                "LIMIT must be an integer", "line 1, column 34");
        assertParseError("SELECT name FROM employees LIMIT -1",
                "LIMIT must be non-negative", "line 1, column 35");
        assertParseError("SELECT name FROM employees LIMIT 2147483648",
                "LIMIT exceeds the maximum supported value", "line 1, column 34");
    }

    @Test
    void rejectsLimitThenOrderAndUnexpectedTrailingTokens() {
        assertParseError("SELECT name FROM employees LIMIT 2 ORDER BY name",
                "Unexpected token after the query", "line 1, column 36");
        assertParseError("SELECT name FROM employees ORDER BY name LIMIT 2 extra",
                "Unexpected token after the query", "line 1, column 50");
    }

    @Test
    void acceptsOptionalTrailingSemicolon() throws SqlParseException {
        assertEquals("people", parse("SELECT id FROM people;").tableName());
        assertEquals("people", parse("SELECT id FROM people").tableName());
    }

    @Test
    void recognizesKeywordsCaseInsensitively() throws SqlParseException {
        SelectQuery query = parse("sElEcT id fRoM people wHeRe id = 2 gRoUp bY id");

        assertEquals("people", query.tableName());
        assertEquals(List.of("id"), query.groupByColumns());
        assertTrue(query.where().isPresent());
    }

    @Test
    void acceptsWhereFollowedByGroupByInClauseOrder() throws SqlParseException {
        SelectQuery query = parse("SELECT id FROM t WHERE id > 0 GROUP BY id");

        assertEquals(List.of("id"), query.groupByColumns());
        assertTrue(query.where().isPresent());
    }

    @Test
    void rejectsMissingSelectKeywordWithLocation() {
        assertParseError("id FROM t", "Expected SELECT", "line 1, column 1");
    }

    @Test
    void rejectsMissingFromKeyword() {
        assertParseError("SELECT id t", "Expected FROM", "line 1, column 11");
    }

    @Test
    void rejectsMissingTableName() {
        assertParseError("SELECT id FROM", "Expected a table name", "line 1, column 15");
    }

    @Test
    void rejectsMissingSelectExpressions() {
        assertParseError("SELECT FROM t", "Expected a column or aggregate", "line 1, column 8");
        assertParseError("SELECT id, FROM t", "Expected a column or aggregate", "line 1, column 12");
    }

    @Test
    void rejectsMissingWhereExpressions() {
        assertParseError("SELECT id FROM t WHERE", "Expected a column name", "line 1, column 23");
        assertParseError("SELECT id FROM t WHERE id =", "Expected a string, integer, or decimal",
                "line 1, column 28");
    }

    @Test
    void rejectsMissingAggregateArgumentsAndParentheses() {
        assertParseError("SELECT COUNT() FROM t", "Expected a column name", "line 1, column 14");
        assertParseError("SELECT COUNT id FROM t", "Expected '('", "line 1, column 14");
        assertParseError("SELECT SUM(*) FROM t", "Only COUNT accepts '*'", "line 1, column 12");
        assertParseError("SELECT COUNT(id FROM t", "Expected ')'", "line 1, column 17");
    }

    @Test
    void rejectsMissingOrMisplacedConditionParentheses() {
        assertParseError("SELECT id FROM t WHERE (id = 1", "Expected ')'", "line 1, column 31");
        assertParseError("SELECT id FROM t WHERE id = 1)", "Unexpected token", "line 1, column 30");
        assertParseError("SELECT id FROM t WHERE ()", "Expected a column name", "line 1, column 25");
    }

    @Test
    void rejectsUnexpectedTrailingTokens() {
        assertParseError("SELECT id FROM t extra", "Unexpected token", "line 1, column 18");
        assertParseError("SELECT id FROM t; extra", "Unexpected token", "line 1, column 19");
    }

    @Test
    void rejectsInvalidClauseOrdering() {
        assertParseError("SELECT id FROM t GROUP BY id WHERE id = 1",
                "Unexpected token", "line 1, column 30");
        assertParseError("SELECT id GROUP BY id FROM t",
                "Expected FROM", "line 1, column 11");
    }

    @Test
    void rejectsEmptyAndWhitespaceOnlyInput() {
        assertParseError("", "Expected SELECT", "line 1, column 1");
        assertParseError(" \r\n\t", "Expected SELECT", "line 2, column 2");
    }

    @Test
    void acceptsTokenSequenceFromExistingTokenizer() throws SqlParseException {
        List<Token> tokens = new SqlTokenizer().tokenize("SELECT id FROM t");

        assertEquals("t", parser.parse(tokens).tableName());
    }

    @Test
    void rejectsTokensAfterEndOfInput() throws SqlParseException {
        List<Token> tokens = new SqlTokenizer().tokenize("SELECT id FROM t");
        List<Token> malformed = new java.util.ArrayList<>(tokens);
        malformed.add(tokens.get(tokens.size() - 1));

        SqlParseException exception = assertThrows(SqlParseException.class, () -> parser.parse(malformed));
        assertTrue(exception.getMessage().contains("Unexpected token after EOF"));
    }

    private SelectQuery parse(String sql) throws SqlParseException {
        return parser.parse(sql);
    }

    private ComparisonExpression whereComparison(String condition) throws SqlParseException {
        return assertInstanceOf(ComparisonExpression.class,
                parse("SELECT id FROM t WHERE " + condition).where().orElseThrow());
    }

    private void assertParseError(String sql, String message, String location) {
        SqlParseException exception = assertThrows(SqlParseException.class, () -> parser.parse(sql));
        assertTrue(exception.getMessage().contains(message), exception.getMessage());
        assertTrue(exception.getMessage().contains(location), exception.getMessage());
    }
}
