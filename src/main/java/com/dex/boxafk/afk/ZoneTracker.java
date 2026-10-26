package com.dex.boxafk.afk;

import com.dex.boxafk.config.PluginConfig;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.SessionManager;
import com.sk89q.worldguard.session.handler.Handler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds out who stands in an AFK region. WorldGuard already works out region changes on every move for its own
 * flags; a session handler hears about them and only then is the player's position queried once, on the next tick.
 * Players online when the plugin was enabled have WorldGuard sessions created without this handler (plugin
 * reloaders); they are polled each second until they rejoin.
 */
public final class ZoneTracker {

    private final Plugin plugin;
    private final AfkService afk;
    private final Factory factory = new Factory();
    private PluginConfig config;

    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean flushScheduled;
    private final Set<UUID> polled = new HashSet<>();

    public ZoneTracker(Plugin plugin, AfkService afk, PluginConfig config) {
        this.plugin = plugin;
        this.afk = afk;
        this.config = config;
    }

    public void apply(PluginConfig config) {
        this.config = config;
    }

    public void register() {
        sessionManager().registerHandler(factory, null);
        for (Player p : Bukkit.getOnlinePlayers()) polled.add(p.getUniqueId());
    }

    public void unregister() {
        sessionManager().unregisterHandler(factory);
        pending.clear();
        polled.clear();
    }

    private static SessionManager sessionManager() {
        return WorldGuard.getInstance().getPlatform().getSessionManager();
    }

    /** Checks the player on the next tick; many calls in one tick give one check. Safe from any thread. */
    public void queue(UUID uuid) {
        if (!plugin.isEnabled()) return; // handlers stay on old WorldGuard sessions after a disable
        pending.add(uuid);
        if (flushScheduled) return;
        flushScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            flushScheduled = false;
            for (UUID id : List.copyOf(pending)) {
                pending.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p != null) check(p);
            }
        });
    }

    public void check(Player player) {
        afk.update(player, isInAfkRegion(player));
    }

    public void checkAll() {
        for (Player p : Bukkit.getOnlinePlayers()) check(p);
    }

    /** Called every second: players without the WorldGuard handler. */
    public void poll() {
        if (polled.isEmpty()) return;
        for (UUID uuid : List.copyOf(polled)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                polled.remove(uuid);
            } else {
                check(p);
            }
        }
    }

    public void onQuit(UUID uuid) {
        polled.remove(uuid);
        pending.remove(uuid);
    }

    private boolean isInAfkRegion(Player player) {
        if (player.isDead()) return false;
        String world = player.getWorld().getName();
        if (!config.regions().containsKey(world.toLowerCase(Locale.ROOT))) return false;
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        ApplicableRegionSet set = container.createQuery().getApplicableRegions(BukkitAdapter.adapt(player.getLocation()));
        for (ProtectedRegion region : set) {
            if (config.isAfkRegion(world, region.getId())) return true;
        }
        return false;
    }

    /** "world:region" of every configured region that exists; logs the missing ones. */
    public List<String> validateRegions() {
        List<String> found = new ArrayList<>();
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        for (Map.Entry<String, Set<String>> e : config.regions().entrySet()) {
            World world = findWorld(e.getKey());
            RegionManager manager = world == null ? null : container.get(BukkitAdapter.adapt(world));
            for (String id : e.getValue()) {
                if (manager != null && manager.hasRegion(id)) {
                    found.add(world.getName() + ":" + id);
                } else {
                    plugin.getLogger().warning("AFK region '" + id + "' in world '" + e.getKey()
                            + "' does not exist (create it with WorldGuard, then /afk reload). Skipped.");
                }
            }
        }
        return found;
    }

    private static World findWorld(String lowerName) {
        for (World w : Bukkit.getWorlds()) {
            if (w.getName().equalsIgnoreCase(lowerName)) return w;
        }
        return null;
    }

    private final class Factory extends Handler.Factory<ZoneHandler> {
        @Override
        public ZoneHandler create(Session session) {
            return new ZoneHandler(session);
        }
    }

    private final class ZoneHandler extends Handler {

        ZoneHandler(Session session) {
            super(session);
        }

        @Override
        public void initialize(LocalPlayer player, Location current, ApplicableRegionSet set) {
            queue(player.getUniqueId());
        }

        @Override
        public boolean onCrossBoundary(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet,
                                       Set<ProtectedRegion> entered, Set<ProtectedRegion> exited, MoveType moveType) {
            // Checked on the next tick from the real position, so a move another handler cancels counts right.
            queue(player.getUniqueId());
            return true;
        }
    }
}
