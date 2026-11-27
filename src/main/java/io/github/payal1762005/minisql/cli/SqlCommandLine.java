package io.github.payal1762005.minisql.cli;

import io.github.payal1762005.minisql.engine.QueryEngine;
import io.github.payal1762005.minisql.exception.SqlExecutionException;
import io.github.payal1762005.minisql.exception.SqlParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/**
 * Interactive terminal for submitting SQL to a {@link QueryEngine}.
 */
public final class SqlCommandLine {
    private static final String PRIMARY_PROMPT = "sql> ";
    private static final String CONTINUATION_PROMPT = "...> ";

    private final QueryEngine engine;
    private final Path dataDirectory;
    private final BufferedReader input;
    private final PrintWriter output;
    private final AsciiTableFormatter formatter;
    private final StringBuilder statement = new StringBuilder();
    private boolean inString;

    public SqlCommandLine(QueryEngine engine, Path dataDirectory, Reader input, PrintWriter output) {
        this.engine = Objects.requireNonNull(engine, "Query engine must not be null");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "Data directory must not be null")
                .toAbsolutePath().normalize();
        this.input = input instanceof BufferedReader bufferedReader
                ? bufferedReader : new BufferedReader(Objects.requireNonNull(input, "Input must not be null"));
        this.output = Objects.requireNonNull(output, "Output must not be null");
        this.formatter = new AsciiTableFormatter();
    }

    public void run() throws IOException {
        output.println("Mini SQL Query Executor");
        output.println("Enter SQL terminated by ';'. Type help for commands.");
        output.flush();

        String line;
        boolean exiting = false;
        while (!exiting) {
            output.print(statement.isEmpty() ? PRIMARY_PROMPT : CONTINUATION_PROMPT);
            output.flush();
            line = input.readLine();
            if (line == null) {
                break;
            }
            if (statement.isEmpty()) {
                Command command = command(line);
                if (command != Command.NONE) {
                    exiting = runCommand(command);
                    continue;
                }
            }
            processLine(line);
        }

        if (!statement.toString().isBlank()) {
            output.println("Input ended before the SQL statement was terminated with ';'.");
        }
        output.println("Goodbye.");
        output.flush();
    }

    private void processLine(String line) {
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            statement.append(character);
            if (inString) {
                if (character == '\'') {
                    if (index + 1 < line.length() && line.charAt(index + 1) == '\'') {
                        statement.append(line.charAt(++index));
                    } else {
                        inString = false;
                    }
                }
            } else if (character == '\'') {
                inString = true;
            } else if (character == ';') {
                executeStatement();
            }
        }
        if (!statement.isEmpty()) {
            statement.append(System.lineSeparator());
        }
    }

    private void executeStatement() {
        String sql = statement.toString();
        statement.setLength(0);
        inString = false;
        if (sql.isBlank()) {
            return;
        }
        try {
            output.println(formatter.format(engine.execute(sql)));
        } catch (SqlParseException exception) {
            output.println("Parse error: " + usefulMessage(exception));
        } catch (SqlExecutionException exception) {
            output.println("Execution error: " + usefulMessage(exception));
        } catch (RuntimeException exception) {
            output.println("Execution error: " + usefulMessage(exception));
        }
        output.flush();
    }

    private Command command(String line) {
        String value = line.trim();
        if (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "help" -> Command.HELP;
            case "tables" -> Command.TABLES;
            case "exit" -> Command.EXIT;
            default -> Command.NONE;
        };
    }

    private boolean runCommand(Command command) {
        switch (command) {
            case HELP -> {
                output.println("Commands:");
                output.println("  help   Show this help");
                output.println("  tables List CSV tables in " + dataDirectory);
                output.println("  exit   End the session");
                output.println("Enter SQL statements terminated by ';'. Statements may span lines.");
            }
            case TABLES -> listTables();
            case EXIT -> {
                return true;
            }
            case NONE -> {
                return false;
            }
        }
        output.flush();
        return false;
    }

    private void listTables() {
        if (!Files.isDirectory(dataDirectory)) {
            output.println("Table listing error: data directory does not exist: " + dataDirectory);
            return;
        }
        try (var files = Files.list(dataDirectory)) {
            var tables = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".csv"))
                    .map(name -> name.substring(0, name.length() - ".csv".length()))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (tables.isEmpty()) {
                output.println("No CSV tables found.");
            } else {
                output.println("Tables:");
                tables.forEach(table -> output.println("  " + table));
            }
        } catch (IOException exception) {
            output.println("Table listing error: " + usefulMessage(exception));
        }
    }

    private String usefulMessage(Throwable failure) {
        String message = failure.getMessage();
        Throwable cause = failure.getCause();
        if (cause != null && cause.getMessage() != null
                && !cause.getMessage().equals(message)) {
            return (message == null ? failure.getClass().getSimpleName() : message)
                    + " (cause: " + cause.getMessage() + ")";
        }
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    private enum Command {
        HELP,
        TABLES,
        EXIT,
        NONE
    }
}
