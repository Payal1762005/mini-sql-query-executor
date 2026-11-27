package io.github.payal1762005.minisql.model;

import java.util.List;
import java.util.Objects;

/**
 * An immutable node in a human-readable logical plan.
 */
public record LogicalPlanNode(String operator, String details, List<LogicalPlanNode> children) {
    public LogicalPlanNode {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("Plan operator must not be blank");
        }
        Objects.requireNonNull(details, "Plan details must not be null");
        children = List.copyOf(Objects.requireNonNull(children, "Plan children must not be null"));
        if (children.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Plan children must not contain null");
        }
    }

    public String format() {
        StringBuilder output = new StringBuilder();
        appendTo(output, 0);
        return output.toString();
    }

    private void appendTo(StringBuilder output, int depth) {
        output.append("  ".repeat(depth)).append(operator);
        if (!details.isEmpty()) {
            output.append(": ").append(details);
        }
        for (LogicalPlanNode child : children) {
            output.append('\n');
            child.appendTo(output, depth + 1);
        }
    }
}
