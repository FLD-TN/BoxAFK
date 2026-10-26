package com.dex.boxafk.hook;

import com.dex.boxafk.BoxAfkPlugin;
import com.dex.boxafk.afk.AfkService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** %boxafk_in_zone|time_left|group_size|reduction|bonus|zone_count%. Read on the main thread (TAB etc. may call async, values are plain reads). */
public final class AfkExpansion extends PlaceholderExpansion {

    private final BoxAfkPlugin plugin;

    public AfkExpansion(BoxAfkPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "boxafk";
    }

    @Override
    public String getAuthor() {
        return "dex";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        AfkService afk = plugin.afk();
        if (params.equals("zone_count")) return String.valueOf(afk.zoneCount());
        if (offline == null) return "";
        return switch (params) {
            case "in_zone" -> String.valueOf(afk.inZone(offline.getUniqueId()));
            case "time_left" -> {
                int left = afk.timeLeft(offline.getUniqueId());
                yield left < 0 ? "" : AfkService.formatTime(left);
            }
            case "group_size" -> {
                int size = plugin.groups().groupOf(offline.getUniqueId()).size();
                yield String.valueOf(Math.max(1, size));
            }
            case "reduction" -> String.valueOf(afk.reduction(offline.getUniqueId()));
            case "bonus" -> offline instanceof Player p ? AfkService.formatPercent(afk.bonus(p)) : "0";
            default -> null;
        };
    }
}
