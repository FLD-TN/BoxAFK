package com.dex.boxafk.group;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Linked account groups: one group per (hashed) IP, cached in memory. Changes happen on the main thread only;
 * reads are also safe from other threads (PlaceholderAPI via TAB) because every map is concurrent and a group is
 * an immutable list replaced on change. Database writes go through {@code writer} (one background thread in
 * production) so they keep their order and never block the server.
 * A group always has at least 2 members: when only one is left it is dissolved.
 */
public final class GroupManager {

    /** Who is left after an account was removed. {@code dissolved} = the last member, removed with the group. */
    public record Removal(List<UUID> remaining, UUID dissolved) {
    }

    private final GroupStorage storage;
    private final Executor writer;
    private final String salt;

    private final Map<UUID, String> hashOf = new ConcurrentHashMap<>();
    /** ip hash -> members in link order (immutable) */
    private final Map<String, List<UUID>> members = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** Current IP hash of online players (linked or not); also the "is online" test of this plugin. */
    private final Map<UUID, String> onlineHash = new ConcurrentHashMap<>();

    public GroupManager(GroupStorage storage, Executor writer, String salt, List<GroupStorage.Row> rows) {
        this.storage = storage;
        this.writer = writer;
        this.salt = salt;
        for (GroupStorage.Row row : rows) {
            hashOf.put(row.uuid(), row.ipHash());
            names.put(row.uuid(), row.name());
            members.merge(row.ipHash(), List.of(row.uuid()), GroupManager::concat);
        }
        // Clean leftovers (e.g. a crash between two writes): groups of one are not groups.
        for (List<UUID> group : List.copyOf(members.values())) {
            if (group.size() < 2) {
                for (UUID uuid : group) forget(uuid);
            }
        }
    }

    private static List<UUID> concat(List<UUID> a, List<UUID> b) {
        List<UUID> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    public String hashIp(String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((salt + ':' + ip).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Records the player's current IP. A linked account coming from another IP leaves its old group.
     *
     * @return the removal when the account left its group, else {@code null}
     */
    public Removal onJoin(UUID uuid, String name, String ipHash) {
        onlineHash.put(uuid, ipHash);
        String linked = hashOf.get(uuid);
        if (linked == null) return null;
        if (!linked.equals(ipHash)) {
            return remove(uuid);
        }
        if (!name.equals(names.get(uuid))) {
            names.put(uuid, name);
            writer.execute(() -> storage.upsert(uuid, ipHash, name));
        }
        return null;
    }

    public void onQuit(UUID uuid) {
        onlineHash.remove(uuid);
    }

    public boolean isOnline(UUID uuid) {
        return onlineHash.containsKey(uuid);
    }

    public String currentHash(UUID uuid) {
        return onlineHash.get(uuid);
    }

    public boolean isLinked(UUID uuid) {
        return hashOf.containsKey(uuid);
    }

    /** The account's group in link order, or an empty list. */
    public List<UUID> groupOf(UUID uuid) {
        String hash = hashOf.get(uuid);
        return hash == null ? List.of() : groupOfHash(hash);
    }

    /** Members of the group linked to this IP hash, or an empty list. */
    public List<UUID> groupOfHash(String hash) {
        return members.getOrDefault(hash, List.of());
    }

    /** Online accounts of this player's group; 1 for an account without a group. */
    public int onlineCount(UUID uuid) {
        List<UUID> group = groupOf(uuid);
        if (group.isEmpty()) return 1;
        int n = 0;
        for (UUID member : group) {
            if (isOnline(member)) n++;
        }
        return Math.max(1, n);
    }

    public String nameOf(UUID uuid) {
        String name = names.get(uuid);
        return name == null ? uuid.toString() : name;
    }

    /** Finds a linked account by its last known name (case-insensitive), for offline admin lookups. */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** Size the group of this IP would have after linking {@code a} and {@code b}. */
    public int sizeAfterLink(String hash, UUID a, UUID b) {
        List<UUID> group = groupOfHash(hash);
        int size = group.size();
        if (!group.contains(a)) size++;
        if (!group.contains(b)) size++;
        return size;
    }

    /** Links both accounts into the group of {@code hash}. Callers check every condition first. */
    public void link(String hash, UUID a, String nameA, UUID b, String nameB) {
        add(hash, a, nameA);
        add(hash, b, nameB);
    }

    private void add(String hash, UUID uuid, String name) {
        List<UUID> group = groupOfHash(hash);
        if (group.contains(uuid) && name.equals(names.get(uuid))) return;
        if (!group.contains(uuid)) members.put(hash, concat(group, List.of(uuid)));
        names.put(uuid, name);
        hashOf.put(uuid, hash);
        writer.execute(() -> storage.upsert(uuid, hash, name));
    }

    /** Removes the account from its group; dissolves the group when one member is left. {@code null} if not linked. */
    public Removal remove(UUID uuid) {
        String hash = hashOf.get(uuid);
        if (hash == null) return null;
        forget(uuid);
        List<UUID> group = groupOfHash(hash);
        if (group.size() == 1) {
            UUID last = group.getFirst();
            forget(last);
            return new Removal(List.of(), last);
        }
        return new Removal(group, null);
    }

    private void forget(UUID uuid) {
        String hash = hashOf.remove(uuid);
        names.remove(uuid);
        if (hash != null) {
            List<UUID> group = groupOfHash(hash);
            List<UUID> rest = new ArrayList<>(group);
            rest.remove(uuid);
            if (rest.isEmpty()) members.remove(hash);
            else members.put(hash, List.copyOf(rest));
        }
        writer.execute(() -> storage.delete(uuid));
    }
}
