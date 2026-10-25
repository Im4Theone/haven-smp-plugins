package net.kaloyan.smpspawners;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Chat messages from the config (MiniMessage) and the small text helpers the menus share. */
public final class Texts {

    /** Colour of headings and numbers on the spawner item. */
    public static final TextColor SKY = TextColor.color(0x38BDF8);
    /** Colour of the spawner item's name. */
    public static final TextColor LIGHT_SKY = TextColor.color(0x7DD3FC);
    /** Colour of the storage chest and numbers in menus. */
    public static final TextColor ORANGE = TextColor.color(0xFFAA00);
    /** Colour of the experience button. */
    public static final TextColor MINT = TextColor.color(0x36FFA4);
    /** Colour of the spawner itself in menus. */
    public static final TextColor TEAL = TextColor.color(0x5EEAD4);

    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Supplier<Settings> settings;

    /** @param settings the current settings */
    public Texts(Supplier<Settings> settings) {
        this.settings = settings;
    }

    /**
     * @param key          the message key
     * @param placeholders name, value pairs (values are plain text)
     * @return the rendered message
     */
    public Component render(String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return mini.deserialize(settings.get().message(key), values.build());
    }

    /**
     * Sends a message.
     *
     * @param to           who gets it
     * @param key          the message key
     * @param placeholders name, value pairs
     */
    public void send(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(render(key, placeholders));
    }

    /** Text without the italics that item names and lore get by default. */
    public static Component text(String text, TextColor color, boolean bold) {
        Component component = Component.text(text, color).decoration(TextDecoration.ITALIC, false);
        return bold ? component.decorate(TextDecoration.BOLD) : component;
    }

    /** White text without italics. */
    public static Component white(String text) {
        return Component.text(text, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * @param id a name in capitals with underscores, e.g. {@code IRON_GOLEM}
     * @return a readable name such as {@code Iron Golem}
     */
    public static String pretty(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /**
     * @param cents an amount in hundredths
     * @return the amount without needless zeros, e.g. {@code 2.5} or {@code 1,234}
     */
    public static String money(long cents) {
        BigDecimal value = BigDecimal.valueOf(cents, 2).stripTrailingZeros();
        return new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }

    /**
     * @param value a percentage
     * @return it with one decimal at most and a percent sign, e.g. {@code 33.3%} or {@code 50%}
     */
    public static String percent(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return (text.endsWith(".0") ? text.substring(0, text.length() - 2) : text) + "%";
    }
}
