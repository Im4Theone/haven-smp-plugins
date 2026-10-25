package net.kaloyan.smpcore.testutil;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Records everything written to a private {@link Logger} so tests can assert on log output. */
public final class LogCapture extends Handler {

    private final Logger logger = Logger.getAnonymousLogger();
    private final List<LogRecord> records = new ArrayList<>();

    public LogCapture() {
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(this);
    }

    public Logger logger() {
        return logger;
    }

    public List<LogRecord> records() {
        return List.copyOf(records);
    }

    /** All captured messages at exactly {@code level}. */
    public List<String> messages(Level level) {
        return records.stream().filter(r -> r.getLevel().equals(level)).map(LogRecord::getMessage).toList();
    }

    /** Every captured message, regardless of level. */
    public List<String> allMessages() {
        return records.stream().map(LogRecord::getMessage).toList();
    }

    @Override
    public void publish(LogRecord record) {
        records.add(record);
    }

    @Override
    public void flush() {
        // nothing buffered
    }

    @Override
    public void close() {
        // nothing to release
    }
}
