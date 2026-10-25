package net.kaloyan.smpspawners;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Creates and recognises spawner items. A spawner item carries its creature in a hidden tag, so only ones made here can be placed. */
public final class SpawnerItems {

    private final NamespacedKey tag;
    private final Supplier<Settings> settings;

    /**
     * @param plugin   the owning plugin
     * @param settings the current settings (for the description)
     */
    public SpawnerItems(Plugin plugin, Supplier<Settings> settings) {
        // the same tag name as the old ISpawners, so spawner items players already hold keep working
        this.tag = new NamespacedKey("ispawners", "spawnertype");
        this.settings = settings;
    }

    /**
     * The description on a spawner item: experience and the possible drops with their amounts and chances.
     *
     * @param kind what the creature produces
     * @return the lore lines
     */
    public static List<Component> description(Kind kind) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Texts.text("DETAIL:", Texts.SKY, true));
        lore.add(bullet(Texts.white("EXP give: ").append(Texts.text(String.valueOf(kind.exp()), Texts.SKY, false))));
        lore.add(Component.empty());
        lore.add(Texts.text("DROPS:", Texts.SKY, true));
        for (Kind.Drop drop : kind.drops()) {
            String amount = drop.min() == drop.max() ? String.valueOf(drop.min()) : drop.min() + "-" + drop.max();
            lore.add(bullet(Texts.white(amount + " " + Texts.pretty(drop.item()) + " ")
                    .append(Texts.text("(" + Texts.percent(kind.percent(drop)) + ")", Texts.SKY, false))));
        }
        return lore;
    }

    private static Component bullet(Component text) {
        return Texts.text(" ▪ ", Texts.SKY, false).append(text);
    }

    /**
     * @param kindId the creature id, e.g. {@code BLAZE}
     * @param amount how many
     * @return a spawner item that places (and stacks) as that creature
     */
    public ItemStack create(String kindId, int amount) {
        ItemStack item = new ItemStack(Material.SPAWNER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.text(Texts.pretty(kindId) + " Spawner", Texts.LIGHT_SKY, false));
        Kind kind = settings.get().kinds().get(kindId);
        if (kind != null) {
            meta.lore(description(kind));
        }
        if (meta instanceof BlockStateMeta blockState && blockState.getBlockState() instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(EntityType.valueOf(kindId));
            blockState.setBlockState(spawner);
        }
        meta.getPersistentDataContainer().set(tag, PersistentDataType.STRING, kindId);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * @param item any stack, may be {@code null}
     * @return the creature id if it is one of our spawner items, else {@code null}
     */
    public String kindOf(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(tag, PersistentDataType.STRING);
        return id == null ? null : id.toUpperCase(Locale.ROOT);
    }
}
