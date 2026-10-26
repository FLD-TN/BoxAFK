package com.dex.boxafk.afk;

import com.dex.boxafk.config.Messages;
import com.dex.boxafk.config.PluginConfig;
import com.dex.boxafk.group.GroupManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Countdown and rewards of players standing in an AFK region. Main thread only; driven by the plugin's one
 * 1-second task. Only players inside a region have a session, so the work per tick scales with them alone.
 */
public final class AfkService {

    /**
     * One player in a region. The reduction is fixed when a countdown starts; group changes apply from the next one.
     * {@code waiting}: the IP already has max-rewarded-per-ip counting players; no countdown until a slot frees.
     */
    private static final class Session {
        final long seq;
        volatile boolean waiting;
        volatile int remaining;
        volatile int reduction;

        Session(long seq) {
            this.seq = seq;
        }
    }

    private final Logger log;
    private final GroupManager groups;
    private PluginConfig config;
    private Messages messages;
    private Sound sound;

    /** Concurrent: PlaceholderAPI may read it from another thread. */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Fraction of each scaled reward carried to the next one: uuid -> reward id -> carry. Cleared on quit. */
    private final Map<UUID, Map<String, Double>> carry = new HashMap<>();
    /** Entry order: the earliest waiting player of an IP gets the next free slot. */
    private long nextSeq;

    public AfkService(Logger log, GroupManager groups, PluginConfig config, Messages messages) {
        this.log = log;
        this.groups = groups;
        apply(config, messages);
    }

    public void apply(PluginConfig config, Messages messages) {
        this.config = config;
        this.messages = messages;
        this.sound = resolveSound(config.rewardSound());
    }

    private Sound resolveSound(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        // Enum name (BLOCK_NOTE_BLOCK_PLING) or Minecraft key (block.note_block.pling); '_' -> '.' guessing breaks
        // keys like note_block, so the enum name is looked up as it is.
        try {
            return Sound.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // not an enum name, try the key
        }
        NamespacedKey key = NamespacedKey.fromString(trimmed.toLowerCase(Locale.ROOT));
        Sound s = key == null ? null : Registry.SOUNDS.get(key);
        if (s == null) log.warning("reward-sound: unknown sound '" + name + "', disabled.");
        return s;
    }

    public boolean inZone(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public int zoneCount() {
        return sessions.size();
    }

    /** Seconds left of the running countdown, or -1 outside a region or while waiting for a slot. */
    public int timeLeft(UUID uuid) {
        Session s = sessions.get(uuid);
        return s == null || s.waiting ? -1 : s.remaining;
    }

    /** Reduction % of the running countdown, or what a new countdown would get outside a region. */
    public int reduction(UUID uuid) {
        Session s = sessions.get(uuid);
        return s != null ? s.reduction : currentReduction(uuid);
    }

    public int currentReduction(UUID uuid) {
        return config.reductionFor(groups.onlineCount(uuid));
    }

    /** Highest rank-bonus % among the player's permissions. */
    public double bonus(Player player) {
        double best = 0;
        for (Map.Entry<String, Double> e : config.rankBonus().entrySet()) {
            if (e.getValue() > best && player.hasPermission(e.getKey())) best = e.getValue();
        }
        return best;
    }

    /** Called with the result of a region check; starts or cancels the countdown on a change. */
    public void update(Player player, boolean nowInZone) {
        UUID uuid = player.getUniqueId();
        boolean wasInZone = sessions.containsKey(uuid);
        if (nowInZone == wasInZone) return;
        if (nowInZone) {
            Session s = new Session(nextSeq++);
            s.waiting = !hasFreeSlot(uuid);
            sessions.put(uuid, s);
            messages.send(player, "zone-entered");
            sendGroupLine(player, "zone-enter");
            if (s.waiting) {
                messages.send(player, "zone-ip-limit", "max", String.valueOf(config.maxRewardedPerIp()));
            } else {
                restart(uuid, s);
                if (s.reduction > 0) {
                    messages.send(player, "zone-enter-reduction", "reduction", String.valueOf(s.reduction));
                }
            }
            showActionBar(player, s);
        } else {
            sessions.remove(uuid);
            messages.send(player, "zone-leave");
            player.sendActionBar(Component.empty());
        }
    }

    /** Whether the player's IP has fewer than max-rewarded-per-ip counting players (the player not included). */
    private boolean hasFreeSlot(UUID uuid) {
        int max = config.maxRewardedPerIp();
        String hash = groups.currentHash(uuid);
        if (max <= 0 || hash == null) return true;
        int counting = 0;
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (!e.getValue().waiting && !e.getKey().equals(uuid) && hash.equals(groups.currentHash(e.getKey()))) {
                counting++;
            }
        }
        return counting < max;
    }

    /** Gives freed slots to waiting players, earliest entry first. */
    private void promoteWaiting() {
        int max = config.maxRewardedPerIp();
        List<Map.Entry<UUID, Session>> waiting = new ArrayList<>();
        Map<String, Integer> counting = new HashMap<>();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().waiting) {
                waiting.add(e);
            } else {
                String hash = groups.currentHash(e.getKey());
                if (hash != null) counting.merge(hash, 1, Integer::sum);
            }
        }
        if (waiting.isEmpty()) return;
        waiting.sort(Comparator.comparingLong(e -> e.getValue().seq));
        for (Map.Entry<UUID, Session> e : waiting) {
            String hash = groups.currentHash(e.getKey());
            if (max > 0 && hash != null && counting.getOrDefault(hash, 0) >= max) continue;
            if (hash != null) counting.merge(hash, 1, Integer::sum);
            Session s = e.getValue();
            s.waiting = false;
            restart(e.getKey(), s);
        }
    }

    private void restart(UUID uuid, Session s) {
        s.reduction = currentReduction(uuid);
        s.remaining = config.intervalFor(s.reduction);
    }

    /** "2/3 - Dex, Dex_alt1" for the player's group (just the player when not linked). */
    public void sendGroupLine(Player player, String key) {
        List<UUID> group = groups.groupOf(player.getUniqueId());
        String names;
        int count;
        if (group.isEmpty()) {
            names = player.getName();
            count = 1;
        } else {
            List<String> list = new ArrayList<>(group.size());
            for (UUID member : group) list.add(groups.nameOf(member));
            names = String.join(", ", list);
            count = group.size();
        }
        messages.send(player, key,
                "count", String.valueOf(count),
                "max", String.valueOf(config.maxGroupSize()),
                "names", names);
    }

    /** Quit: the countdown is lost, nothing is kept for this player. */
    public void onQuit(UUID uuid) {
        sessions.remove(uuid);
        carry.remove(uuid);
    }

    public void tick() {
        if (sessions.isEmpty()) return;
        promoteWaiting();
        // Copy: reward commands may teleport or kick a player, which changes the map.
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Session s = sessions.get(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (s == null) continue;
            if (player == null) {
                sessions.remove(uuid);
                continue;
            }
            if (s.waiting) {
                showActionBar(player, s);
                continue;
            }
            s.remaining--;
            if (s.remaining <= 0) {
                reward(player);
                if (sessions.get(uuid) != s) continue; // left the region or quit during the commands
                restart(uuid, s);
            }
            showActionBar(player, s);
        }
    }

    private void showActionBar(Player player, Session s) {
        if (s.waiting) {
            messages.actionBar(player, "actionbar-waiting", "max", String.valueOf(config.maxRewardedPerIp()));
            return;
        }
        messages.actionBar(player, "actionbar",
                "time", formatTime(s.remaining),
                "reduction", String.valueOf(s.reduction));
    }

    public static String formatTime(int seconds) {
        int s = Math.max(0, seconds);
        int m = s / 60;
        int r = s % 60;
        return (m < 10 ? "0" : "") + m + ":" + (r < 10 ? "0" : "") + r;
    }

    private void reward(Player player) {
        String name = player.getName();
        double bonus = bonus(player);
        Map<String, Double> carried = carry.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        for (ScaledReward r : config.scaledRewards()) {
            double[] split = ScaledReward.split(r.amount(), bonus, carried.getOrDefault(r.id(), 0.0));
            carried.put(r.id(), split[1]);
            long give = (long) split[0];
            if (give <= 0) continue;
            for (String command : r.commands()) {
                run(command.replace("%amount%", String.valueOf(give)), name);
            }
        }
        for (String command : config.fixedRewards()) {
            run(command, name);
        }
        if (!player.isOnline()) return;
        if (sound != null) player.playSound(player.getLocation(), sound, 1f, 1f);
    }

    public static String formatPercent(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private void run(String command, String playerName) {
        String cmd = command.replace("%player_name%", playerName).replace("%player%", playerName);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Exception e) {
            log.log(Level.WARNING, "Reward command failed: " + cmd, e);
        }
    }

    public void clear() {
        sessions.clear();
        carry.clear();
    }
}
