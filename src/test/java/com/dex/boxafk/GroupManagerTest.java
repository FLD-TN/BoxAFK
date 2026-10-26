package com.dex.boxafk;

import com.dex.boxafk.group.GroupManager;
import com.dex.boxafk.group.GroupStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupManagerTest {

    @TempDir
    Path dir;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private GroupStorage open() throws Exception {
        GroupStorage storage = new GroupStorage(Logger.getLogger("test"));
        storage.open(new File(dir.toFile(), "data.db"));
        return storage;
    }

    private GroupManager manager(GroupStorage storage) throws Exception {
        return new GroupManager(storage, Runnable::run, storage.salt(), storage.loadAll());
    }

    @Test
    void linkCountAndPersist() throws Exception {
        GroupStorage storage = open();
        GroupManager g = manager(storage);
        String ip = g.hashIp("1.2.3.4");
        g.onJoin(a, "A", ip);
        g.onJoin(b, "B", ip);
        g.onJoin(c, "C", ip);
        g.link(ip, a, "A", b, "B");
        assertEquals(List.of(a, b), g.groupOf(a));
        assertEquals(3, g.sizeAfterLink(ip, b, c));
        g.link(ip, b, "B", c, "C");
        assertEquals(3, g.onlineCount(c));
        g.onQuit(a);
        assertEquals(2, g.onlineCount(c));

        // Reload from disk: same groups, same salt -> same hash.
        GroupManager reloaded = manager(storage);
        assertEquals(List.of(a, b, c), reloaded.groupOf(a));
        assertEquals(ip, reloaded.hashIp("1.2.3.4"));
        storage.close();
    }

    @Test
    void ipChangeRemovesAndDissolves() throws Exception {
        GroupStorage storage = open();
        GroupManager g = manager(storage);
        String ip = g.hashIp("1.2.3.4");
        g.onJoin(a, "A", ip);
        g.onJoin(b, "B", ip);
        g.link(ip, a, "A", b, "B");

        GroupManager.Removal r = g.onJoin(a, "A", g.hashIp("5.6.7.8"));
        assertNotNull(r);
        assertEquals(b, r.dissolved());
        assertFalse(g.isLinked(a));
        assertFalse(g.isLinked(b));
        assertTrue(manager(storage).groupOf(b).isEmpty());
        storage.close();
    }

    @Test
    void unlinkKeepsGroupOfTwo() throws Exception {
        GroupStorage storage = open();
        GroupManager g = manager(storage);
        String ip = g.hashIp("1.2.3.4");
        g.link(ip, a, "A", b, "B");
        g.link(ip, a, "A", c, "C");
        GroupManager.Removal r = g.remove(c);
        assertNull(r.dissolved());
        assertEquals(List.of(a, b), r.remaining());
        assertNull(g.remove(c));
        assertEquals(1, g.onlineCount(c));
        storage.close();
    }
}
