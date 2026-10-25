package net.kaloyan.smpscoreboard;

import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Reads LuckPerms prefixes: {@code &} colour codes and {@code &#RRGGBB} hex colours. */
public final class Prefixes {

    private static final LegacyComponentSerializer SERIALIZER =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Prefixes() {
    }

    /**
     * @param prefix a LuckPerms prefix such as {@code &#F87171&lAdmin}
     * @return the coloured text
     */
    public static TextComponent parse(String prefix) {
        return SERIALIZER.deserialize(prefix);
    }
}
