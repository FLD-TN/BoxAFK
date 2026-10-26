package com.dex.boxafk;

import com.dex.boxafk.afk.AfkService;
import com.dex.boxafk.afk.ZoneTracker;
import com.dex.boxafk.command.AfkCommand;
import com.dex.boxafk.config.Messages;
import com.dex.boxafk.config.PluginConfig;
import com.dex.boxafk.group.GroupManager;
import com.dex.boxafk.group.GroupStorage;
import com.dex.boxafk.hook.AfkExpansion;
import com.dex.boxafk.link.LinkService;
import com.dex.boxafk.listener.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class BoxAfkPlugin extends JavaPlugin {

    private PluginConfig config;
    private Messages messages;
    private GroupStorage storage;
    private ExecutorService writer;
    private GroupManager groups;
    private AfkService afk;
    private LinkService links;
    private ZoneTracker zones;
    private PlayerListener playerListener;
    private AfkExpansion expansion;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        config = PluginConfig.load(getConfig(), getLogger());
        messages = loadMessages();

        storage = new GroupStorage(getLogger());
        String salt;
        List<GroupStorage.Row> rows;
        try {
            storage.open(new File(getDataFolder(), "data.db"));
            salt = storage.salt();
            rows = storage.loadAll();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Cannot open data.db, BoxAFK is disabled.", e);
            storage.close();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BoxAFK-db");
            t.setDaemon(true);
            return t;
        });
        groups = new GroupManager(storage, writer, salt, rows);
        afk = new AfkService(getLogger(), groups, config, messages);
        links = new LinkService(groups, afk, config, messages);
        zones = new ZoneTracker(this, afk, config);
        playerListener = new PlayerListener(this, groups, afk, links, zones, this::messages);

        getServer().getPluginManager().registerEvents(playerListener, this);
        PluginCommand command = getCommand("afk");
        if (command != null) {
            AfkCommand executor = new AfkCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Players already online (plugin reloaders): record their IP, then poll their position.
        for (Player p : Bukkit.getOnlinePlayers()) playerListener.handleJoin(p);
        zones.register();
        List<String> found = zones.validateRegions();
        getLogger().info("Loaded " + rows.size() + " linked accounts. AFK regions: " + (found.isEmpty() ? "none" : String.join(", ", found)));

        task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            zones.poll();
            links.tick();
            afk.tick();
        }, 20L, 20L);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new AfkExpansion(this);
            expansion.register();
        }
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (expansion != null) expansion.unregister();
        if (zones != null) zones.unregister();
        if (links != null) links.clear();
        if (afk != null) afk.clear();
        if (writer != null) {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().warning("Database writes did not finish in 10 seconds.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (storage != null) storage.close();
    }

    /** Re-reads config.yml and messages.yml. Countdowns of players still inside a region keep running. */
    public List<String> reload() {
        reloadConfig();
        config = PluginConfig.load(getConfig(), getLogger());
        messages = loadMessages();
        afk.apply(config, messages);
        links.apply(config, messages);
        zones.apply(config);
        List<String> found = zones.validateRegions();
        zones.checkAll();
        return found;
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
    }

    private Messages loadMessages() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        try (InputStream in = getResource("messages.yml")) {
            if (in != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not read the bundled messages.yml", e);
        }
        return Messages.of(yaml);
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public GroupManager groups() {
        return groups;
    }

    public AfkService afk() {
        return afk;
    }

    public LinkService links() {
        return links;
    }

    public PlayerListener playerListener() {
        return playerListener;
    }
}
