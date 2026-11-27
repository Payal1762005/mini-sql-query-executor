package io.github.payal1762005.minisql.cli;

import io.github.payal1762005.minisql.engine.DefaultQueryEngine;
import io.github.payal1762005.minisql.storage.CsvTableStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCommandLineTest {
    @TempDir
    Path dataDirectory;

    private StringWriter captured;
    private SqlCommandLine commandLine;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dataDirectory.resolve("employees.csv"),
                "id,name,department,salary\n"
                        + "1,Ava,Engineering,100000\n"
                        + "2,Noah,Sales,80000\n");
    }

    @Test
    void executesSqlAndPrintsAnAsciiResultTable() throws Exception {
        String output = run("SELECT name, salary FROM employees;\nexit\n");

        assertTrue(output.contains("| name | salary |"));
        assertTrue(output.contains("| Ava  | 100000 |"));
        assertTrue(output.contains("(2 rows)"));
    }

    @Test
    void executesSeveralStatementsEnteredOnTheSameLine() throws Exception {
        String output = run("SELECT name FROM employees WHERE id = 1; "
                + "SELECT name FROM employees WHERE id = 2;\nexit\n");

        assertTrue(output.contains("| Ava  |"));
        assertTrue(output.contains("| Noah |"));
        assertEquals(2, output.split("\\(1 row\\)", -1).length - 1);
    }

    @Test
    void executesMultilineAndMultipleStatementsAndSupportsExplain() throws Exception {
        String output = run("SELECT name\n"
                + "FROM employees\n"
                + "WHERE salary > 90000;\n"
                + "EXPLAIN SELECT name FROM employees;\n"
                + "SELECT name FROM employees WHERE name = 'Ava; Engineer';\n"
                + "exit\n");

        assertTrue(output.contains("| Ava  |"));
        assertTrue(output.contains("Projection: name"));
        assertTrue(output.contains("Scan: table=employees"));
        assertTrue(output.contains("(0 rows)"));
    }

    @Test
    void reportsEmptyResultsWithoutTerminatingSession() throws Exception {
        String output = run("SELECT name FROM employees WHERE id = 999;\n"
                + "SELECT name FROM employees WHERE id = 1;\nexit\n");

        assertTrue(output.contains("(0 rows)"));
        assertTrue(output.contains("| Ava  |"));
    }

    @Test
    void recoversFromInvalidSqlAndContinues() throws Exception {
        String output = run("SELECT FROM employees;\nSELECT name FROM employees;\nexit\n");

        assertTrue(output.contains("Parse error:"));
        assertTrue(output.contains("(2 rows)"));
        assertTrue(output.contains("Goodbye."));
    }

    @Test
    void supportsHelpTablesAndExitCommands() throws Exception {
        String output = run("help\ntables\nexit\nSELECT name FROM employees;\n");

        assertTrue(output.contains("Commands:"));
        assertTrue(output.contains("employees"));
        assertTrue(output.contains("Goodbye."));
        assertFalse(output.contains("(2 rows)"));
    }

    @Test
    void reportsUnterminatedStatementAtEndOfInput() throws Exception {
        String output = run("SELECT name FROM employees");

        assertTrue(output.contains("Input ended before the SQL statement was terminated"));
        assertFalse(output.contains("(2 rows)"));
    }

    private String run(String session) throws Exception {
        captured = new StringWriter();
        commandLine = new SqlCommandLine(
                new DefaultQueryEngine(new CsvTableStorage(dataDirectory)),
                dataDirectory,
                new StringReader(session),
                new PrintWriter(captured));
        commandLine.run();
        return captured.toString();
    }
}
