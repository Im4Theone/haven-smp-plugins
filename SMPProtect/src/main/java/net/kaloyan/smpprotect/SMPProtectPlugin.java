package net.kaloyan.smpprotect;

import java.io.IOException;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

/** Protects areas of the server (the hub first) with flags per region. */
public final class SMPProtectPlugin extends JavaPlugin {

    private RegionManager manager;
    private Guard guard;
    private Selections selections;
    private MessageListener messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new RegionManager(new RegionStore(getDataFolder().toPath().resolve("regions.yml")));
        try {
            manager.load();
        } catch (IOException e) {
            // Starting with no regions would silently remove protection, so refuse to run instead.
            getLogger().severe("regions.yml cannot be read: " + e.getMessage());
            getLogger().severe("Fix or restore the file and restart. SMP-Protect is disabled so that no protection is lost silently.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        guard = new Guard(manager);
        selections = new Selections(this);
        applyConfig();
        getServer().getPluginManager().registerEvents(new ProtectionListener(guard, manager), this);
        getServer().getPluginManager().registerEvents(selections, this);
        messages = new MessageListener(manager);
        messages.display(getConfig().getString("messages.greeting-display", "actionbar"));
        getServer().getPluginManager().registerEvents(messages, this);
        ProtectCommand command = new ProtectCommand(manager, selections, this::reloadAll);
        getCommand("protect").setExecutor(command);
        getCommand("protect").setTabCompleter(command);
        getLogger().info(manager.all().size() + " region(s) loaded.");
    }

    private void applyConfig() {
        guard.configure(getConfig().getString("messages.denied", "<red>You can't do that here."),
                Math.max(0, getConfig().getLong("messages.cooldown-ms", 1500)));
        String name = getConfig().getString("wand-material", "BLAZE_ROD").toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem()) {
            getLogger().warning("wand-material '" + name + "' is not an item; using BLAZE_ROD.");
            material = Material.BLAZE_ROD;
        }
        selections.wandMaterial(material);
        if (messages != null) {
            messages.display(getConfig().getString("messages.greeting-display", "actionbar"));
        }
    }

    /**
     * For other plugins (SMP-RTP uses it through reflection): is this block inside a region that does not let players
     * build? Owners and members are not taken into account, so a random teleport never drops anyone into a protected area.
     *
     * @param world the world's name
     * @param x     block X
     * @param y     block Y
     * @param z     block Z
     * @return {@code true} if building is denied there
     */
    public boolean isProtectedSpot(String world, int x, int y, int z) {
        return !manager.index().query(world, x, y, z, Flag.BUILD, null).allowed();
    }

    private void reloadAll() {
        reloadConfig();
        applyConfig();
        try {
            manager.load();
        } catch (IOException e) {
            getLogger().severe("regions.yml could not be reloaded, the previous regions stay active: " + e.getMessage());
        }
    }
}
