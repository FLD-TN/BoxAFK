package com.dex.boxafk.config;

import com.dex.boxafk.afk.ScaledReward;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/** Immutable snapshot of config.yml; {@code /afk reload} builds a new one. */
public record PluginConfig(
        Map<String, Set<String>> regions,
        int rewardInterval,
        TreeMap<Integer, Integer> groupReduction,
        int maxGroupSize,
        int maxRewardedPerIp,
        int linkCooldown,
        int inviteExpire,
        int captchaTime,
        int captchaMin,
        int captchaMax,
        List<ScaledReward> scaledRewards,
        List<String> fixedRewards,
        Map<String, Double> rankBonus,
        String rewardSound
) {

    public static PluginConfig load(FileConfiguration c, Logger log) {
        // world (lower case) -> region ids (lower case, as WorldGuard stores them)
        Map<String, Set<String>> regions = new HashMap<>();
        for (Map<?, ?> entry : c.getMapList("afk-regions")) {
            Object world = entry.get("world");
            Object region = entry.get("region");
            if (world == null || region == null) {
                log.warning("afk-regions: entry without 'world' or 'region' skipped: " + entry);
                continue;
            }
            regions.computeIfAbsent(world.toString().toLowerCase(Locale.ROOT), k -> new HashSet<>())
                    .add(region.toString().toLowerCase(Locale.ROOT));
        }

        TreeMap<Integer, Integer> reduction = new TreeMap<>();
        ConfigurationSection red = c.getConfigurationSection("group-reduction");
        if (red != null) {
            for (String key : red.getKeys(false)) {
                try {
                    int percent = Math.max(0, Math.min(90, red.getInt(key)));
                    reduction.put(Integer.parseInt(key.trim()), percent);
                } catch (NumberFormatException e) {
                    log.warning("group-reduction: '" + key + "' is not a number, skipped.");
                }
            }
        }

        List<ScaledReward> scaled = new ArrayList<>();
        ConfigurationSection sc = c.getConfigurationSection("rewards.scaled");
        if (sc != null) {
            for (String id : sc.getKeys(false)) {
                double amount = sc.getDouble(id + ".amount", 0);
                List<String> commands = sc.getStringList(id + ".commands");
                if (amount <= 0 || commands.isEmpty()) {
                    log.warning("rewards.scaled." + id + ": needs amount > 0 and at least one command, skipped.");
                    continue;
                }
                scaled.add(new ScaledReward(id, amount, List.copyOf(commands)));
            }
        }

        Map<String, Double> bonus = new LinkedHashMap<>();
        ConfigurationSection rb = c.getConfigurationSection("rank-bonus");
        if (rb != null) {
            // Keys contain dots (boxafk.bonus.vip), so read them as deep paths from the section's leaves.
            for (String key : rb.getKeys(true)) {
                if (rb.isConfigurationSection(key)) continue;
                bonus.put(key, Math.max(0, rb.getDouble(key)));
            }
        }

        int captchaMin = c.getInt("link.captcha-min", 1);
        int captchaMax = Math.max(captchaMin, c.getInt("link.captcha-max", 100));

        return new PluginConfig(
                Map.copyOf(regions),
                Math.max(1, c.getInt("reward-interval", 300)),
                reduction,
                Math.max(2, c.getInt("max-group-size", 3)),
                Math.max(0, c.getInt("max-rewarded-per-ip", 3)),
                Math.max(0, c.getInt("link.cooldown", 10)),
                Math.max(5, c.getInt("link.invite-expire", 60)),
                Math.max(5, c.getInt("link.captcha-time", 15)),
                captchaMin,
                captchaMax,
                List.copyOf(scaled),
                List.copyOf(c.getStringList("rewards.fixed")),
                Map.copyOf(bonus),
                c.getString("reward-sound", ""));
    }

    /** % shorter interval for this many linked online accounts: the entry with the largest key <= count. */
    public int reductionFor(int onlineLinked) {
        Map.Entry<Integer, Integer> e = groupReduction.floorEntry(onlineLinked);
        return e == null ? 0 : e.getValue();
    }

    /** Countdown length in seconds after the reduction, at least 1. */
    public int intervalFor(int reductionPercent) {
        return Math.max(1, (int) Math.round(rewardInterval * (100 - reductionPercent) / 100.0));
    }

    public boolean isAfkRegion(String world, String regionId) {
        Set<String> ids = regions.get(world.toLowerCase(Locale.ROOT));
        return ids != null && ids.contains(regionId.toLowerCase(Locale.ROOT));
    }
}
