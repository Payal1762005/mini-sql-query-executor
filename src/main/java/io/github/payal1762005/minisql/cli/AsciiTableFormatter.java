package io.github.payal1762005.minisql.cli;

import io.github.payal1762005.minisql.model.QueryResult;
import io.github.payal1762005.minisql.model.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Renders materialized query results as aligned ASCII tables.
 */
public final class AsciiTableFormatter {
    public String format(QueryResult result) {
        Objects.requireNonNull(result, "Query result must not be null");
        List<Row> rows = result.rows();
        if (rows.isEmpty()) {
            return "(0 rows)";
        }

        List<String> columns = new ArrayList<>(rows.get(0).values().keySet());
        if (columns.isEmpty()) {
            return "(" + rows.size() + (rows.size() == 1 ? " row, no columns)" : " rows, no columns)");
        }

        List<Integer> widths = columns.stream().map(String::length).collect(
                ArrayList::new, ArrayList::add, ArrayList::addAll);
        for (Row row : rows) {
            for (int index = 0; index < columns.size(); index++) {
                widths.set(index, Math.max(widths.get(index),
                        displayValue(row.values().get(columns.get(index))).length()));
            }
        }

        String border = border(widths);
        StringBuilder output = new StringBuilder(border).append('\n');
        appendRow(output, columns, widths);
        output.append(border).append('\n');
        for (Row row : rows) {
            List<String> values = columns.stream()
                    .map(column -> displayValue(row.values().get(column)))
                    .toList();
            appendRow(output, values, widths);
        }
        output.append(border).append('\n')
                .append('(').append(rows.size()).append(rows.size() == 1 ? " row)" : " rows)");
        return output.toString();
    }

    private String border(List<Integer> widths) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append('+');
        }
        return border.toString();
    }

    private void appendRow(StringBuilder output, List<String> values, List<Integer> widths) {
        output.append('|');
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            output.append(' ').append(value)
                    .append(" ".repeat(widths.get(index) - value.length() + 1)).append('|');
        }
        output.append('\n');
    }

    private String displayValue(String value) {
        if (value == null) {
            return "NULL";
        }
        return value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
