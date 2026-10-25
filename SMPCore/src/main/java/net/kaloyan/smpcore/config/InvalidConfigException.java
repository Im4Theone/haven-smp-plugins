package net.kaloyan.smpcore.config;

import java.util.List;

/**
 * Thrown when {@code config.yml} cannot be read or contains invalid values. Carries every problem
 * found, not just the first, so admins can fix the file in one pass.
 */
public final class InvalidConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String[] problems;

    InvalidConfigException(List<String> problems) {
        this(problems, null);
    }

    InvalidConfigException(List<String> problems, Throwable cause) {
        super(String.join("; ", problems), cause);
        this.problems = problems.toArray(String[]::new);
    }

    /**
     * Returns the human-readable problems, in the order they were found.
     *
     * @return an unmodifiable, non-empty list
     */
    public List<String> problems() {
        return List.of(problems);
    }
}
