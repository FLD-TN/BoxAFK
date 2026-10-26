package com.dex.boxafk.command;

import com.dex.boxafk.BoxAfkPlugin;
import com.dex.boxafk.config.Messages;
import com.dex.boxafk.group.GroupManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AfkCommand implements TabExecutor {

    private static final String USE = "boxafk.use";
    private static final String ADMIN = "boxafk.admin";

    private final BoxAfkPlugin plugin;

    public AfkCommand(BoxAfkPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages m = plugin.messages();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link", "accept", "deny", "unlink", "info" -> {
                if (!(sender instanceof Player player)) {
                    m.send(sender, "player-only");
                    return true;
                }
                if (!player.hasPermission(USE)) {
                    m.send(player, "no-permission");
                    return true;
                }
                switch (sub) {
                    case "link" -> {
                        if (args.length < 2) m.send(player, "link-usage");
                        else plugin.links().link(player, args[1]);
                    }
                    case "accept" -> plugin.links().accept(player);
                    case "deny" -> plugin.links().deny(player);
                    case "unlink" -> unlink(player);
                    default -> info(player, player.getUniqueId());
                }
            }
            case "admin" -> admin(sender, args);
            case "reload" -> {
                if (!sender.hasPermission(ADMIN)) {
                    m.send(sender, "no-permission");
                    return true;
                }
                List<String> regions = plugin.reload();
                plugin.messages().send(sender, "reloaded", "regions", regions.isEmpty() ? "-" : String.join(", ", regions));
            }
            default -> {
                m.sendList(sender, "usage");
                if (sender.hasPermission(ADMIN)) m.sendList(sender, "usage-admin");
            }
        }
        return true;
    }

    private void unlink(Player player) {
        GroupManager.Removal removal = plugin.groups().remove(player.getUniqueId());
        if (removal == null) {
            plugin.messages().send(player, "not-linked");
            return;
        }
        plugin.messages().send(player, "unlink-success");
        plugin.playerListener().notifyRemoval(player.getName(), removal);
    }

    private void info(CommandSender to, UUID uuid) {
        Messages m = plugin.messages();
        GroupManager groups = plugin.groups();
        List<UUID> group = groups.groupOf(uuid);
        if (group.isEmpty()) {
            if (to instanceof Player p && p.getUniqueId().equals(uuid)) {
                m.send(to, "not-linked");
            }
            return;
        }
        m.sendLine(to, "info-header",
                "count", String.valueOf(group.size()),
                "max", String.valueOf(plugin.config().maxGroupSize()));
        for (UUID member : group) {
            String status;
            if (plugin.afk().inZone(member)) status = m.raw("status-afk");
            else if (Bukkit.getPlayer(member) != null) status = m.raw("status-online");
            else status = m.raw("status-offline");
            m.sendLine(to, "info-entry", "player", groups.nameOf(member), "status", status);
        }
    }

    private void admin(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        if (!sender.hasPermission(ADMIN)) {
            m.send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            m.sendList(sender, "usage-admin");
            return;
        }
        String name = args[2];
        UUID uuid = resolve(name);
        if (uuid == null) {
            m.send(sender, "admin-unknown-player", "player", name);
            return;
        }
        GroupManager groups = plugin.groups();
        if (!groups.isLinked(uuid)) {
            m.send(sender, "admin-not-linked", "player", name);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "info" -> info(sender, uuid);
            case "unlink" -> {
                String display = groups.nameOf(uuid);
                GroupManager.Removal removal = groups.remove(uuid);
                m.send(sender, "admin-unlinked", "player", display);
                Player target = Bukkit.getPlayer(uuid);
                if (target != null) m.send(target, "unlink-success");
                if (removal != null) plugin.playerListener().notifyRemoval(display, removal);
            }
            default -> m.sendList(sender, "usage-admin");
        }
    }

    /** Online player first, then the last known name of a linked account. Never a Mojang lookup. */
    private UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        UUID linked = plugin.groups().findByName(name);
        if (linked != null) return linked;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission(USE)) options.addAll(List.of("link", "accept", "deny", "unlink", "info"));
            if (sender.hasPermission(ADMIN)) options.addAll(List.of("admin", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("link")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(sender) && (!(sender instanceof Player s) || s.canSee(p))) options.add(p.getName());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN)) {
            options.addAll(List.of("info", "unlink"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN)) {
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }
}
