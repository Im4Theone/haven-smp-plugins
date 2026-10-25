package net.kaloyan.smpauth;

import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/** Keeps passwords out of the console and log files: the server logs "X issued server command: /login password"; those lines are dropped. */
public final class PasswordLogFilter extends AbstractFilter {

    private static final Pattern SENSITIVE = Pattern.compile(
            "issued server command: /?(?:[\\w.-]+:)?(?:login|l|register|reg|changepassword|changepass|cp|unregister|auth\\s+setpassword)(?:\\s|$)");

    /**
     * @param line a line the server is about to log
     * @return whether it contains a password and must not be logged
     */
    public static boolean sensitive(String line) {
        return line != null && SENSITIVE.matcher(line.toLowerCase(Locale.ROOT)).find();
    }

    /** @return the verdict for a line (public for the tests of the message format) */
    static Result decide(String line) {
        return sensitive(line) ? Result.DENY : Result.NEUTRAL;
    }

    @Override
    public Result filter(LogEvent event) {
        return event == null || event.getMessage() == null ? Result.NEUTRAL : decide(event.getMessage().getFormattedMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return msg == null ? Result.NEUTRAL : decide(msg.getFormattedMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return msg == null ? Result.NEUTRAL : decide(msg.toString());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        if (msg == null) {
            return Result.NEUTRAL;
        }
        String all = msg;
        if (params != null) {
            for (Object p : params) {
                int at = all.indexOf("{}");
                all = at < 0 ? all + " " + p : all.substring(0, at) + p + all.substring(at + 2);
            }
        }
        return decide(all);
    }

    /** Installs the filter on the server's root logger. */
    public static Filter install() {
        PasswordLogFilter filter = new PasswordLogFilter();
        ((Logger) org.apache.logging.log4j.LogManager.getRootLogger()).addFilter(filter);
        return filter;
    }
}
