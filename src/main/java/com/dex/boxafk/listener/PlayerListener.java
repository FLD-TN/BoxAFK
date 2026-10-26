package com.dex.boxafk.listener;

import com.dex.boxafk.afk.AfkService;
import com.dex.boxafk.afk.ZoneTracker;
import com.dex.boxafk.config.Messages;
import com.dex.boxafk.group.GroupManager;
import com.dex.boxafk.link.LinkService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

public final class PlayerListener implements Listener {

    private final Plugin plugin;
    private final GroupManager groups;
    private final AfkService afk;
    private final LinkService links;
    private final ZoneTracker zones;
    private final Supplier<Messages> messages;

    public PlayerListener(Plugin plugin, GroupManager groups, AfkService afk, LinkService links, ZoneTracker zones,
                          Supplier<Messages> messages) {
        this.plugin = plugin;
        this.groups = groups;
        this.afk = afk;
        this.links = links;
        this.zones = zones;
        this.messages = messages;
    }

    /** LOWEST: the IP and group are settled before other plugins and the zone check (next tick) look at them. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        handleJoin(event.getPlayer());
    }

    public void handleJoin(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return;
        String hash = groups.hashIp(address.getAddress().getHostAddress());
        GroupManager.Removal removal = groups.onJoin(player.getUniqueId(), player.getName(), hash);
        if (removal == null) return;
        messages.get().send(player, "ip-changed");
        notifyRemoval(player.getName(), removal);
    }

    /** Tells the rest of the group that {@code name} left (or that the group is gone). */
    public void notifyRemoval(String name, GroupManager.Removal removal) {
        Messages m = messages.get();
        for (UUID member : removal.remaining()) {
            Player p = Bukkit.getPlayer(member);
            if (p != null) m.send(p, "unlink-notify", "player", name);
        }
        if (removal.dissolved() != null) {
            Player last = Bukkit.getPlayer(removal.dissolved());
            if (last != null) {
                m.send(last, "unlink-notify", "player", name);
                m.send(last, "group-dissolved");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        links.onQuit(uuid);
        afk.onQuit(uuid);
        zones.onQuit(uuid);
        groups.onQuit(uuid);
    }

    // WorldGuard reports these too; checking again is one query on a rare event.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        zones.queue(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        zones.queue(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        zones.queue(event.getPlayer().getUniqueId());
    }

    /**
     * Captcha input. The legacy event is fired before Paper's AsyncChatEvent and is what many chat plugins use, so it
     * is cancelled here at LOWEST: the number never reaches the public chat.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!links.inCaptcha(player.getUniqueId())) return;
        event.setCancelled(true);
        String message = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) links.onCaptchaChat(player, message);
        });
    }

    /** Safety net for chat plugins on the Paper event: also cancelled, handled once by the legacy listener. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (links.inCaptcha(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
}
