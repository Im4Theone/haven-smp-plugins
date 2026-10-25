package net.kaloyan.smptravel;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** The texts from {@code config.yml}, rendered with MiniMessage. Values are inserted as plain text. */
public final class Messages {

    private final MiniMessage mini = MiniMessage.miniMessage();
    private volatile Map<String, String> texts = Map.of();

    /** @param texts MiniMessage text per key */
    public void texts(Map<String, String> texts) {
        this.texts = Map.copyOf(texts);
    }

    /** @return the raw text for a key, or the key itself if the config lacks it */
    public String raw(String key) {
        return texts.getOrDefault(key, key);
    }

    /**
     * @param key          the message key
     * @param placeholders name, value pairs
     * @return the rendered message
     */
    public Component render(String key, String... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        }
        return mini.deserialize(raw(key), values.build());
    }

    /**
     * Sends a message.
     *
     * @param to           who gets it
     * @param key          the message key
     * @param placeholders name, value pairs
     */
    public void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(render(key, placeholders));
    }
}
