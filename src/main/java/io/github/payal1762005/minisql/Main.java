package io.github.payal1762005.minisql;

import io.github.payal1762005.minisql.cli.SqlCommandLine;
import io.github.payal1762005.minisql.engine.DefaultQueryEngine;
import io.github.payal1762005.minisql.storage.CsvTableStorage;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Usage: java ... io.github.payal1762005.minisql.Main [data-directory]");
            return;
        }
        Path dataDirectory = args.length == 1
                ? Path.of(args[0])
                : Path.of("src", "main", "resources", "data");
        var storage = new CsvTableStorage(dataDirectory);
        var engine = new DefaultQueryEngine(storage);
        var commandLine = new SqlCommandLine(engine, dataDirectory,
                new InputStreamReader(System.in, StandardCharsets.UTF_8),
                new PrintWriter(System.out, true));
        try {
            commandLine.run();
        } catch (IOException exception) {
            System.err.println("CLI input/output error: " + exception.getMessage());
        }
    }
}
