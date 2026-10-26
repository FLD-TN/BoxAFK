package com.dex.boxafk.link;

import com.dex.boxafk.afk.AfkService;
import com.dex.boxafk.config.Messages;
import com.dex.boxafk.config.PluginConfig;
import com.dex.boxafk.group.GroupManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /afk link -> target clicks [Xác nhận] -> types the number shown in a title -> linked.
 * Main thread only, except {@link #inCaptcha} which the async chat listener reads.
 * Every pending state is keyed by UUID, expires in {@link #tick} and is dropped on quit.
 */
public final class LinkService {

    private record Invite(UUID sender, String senderName, long expiresAt) {
    }

    private record Captcha(UUID sender, String senderName, int number, long expiresAt) {
    }

    private static final Title.Times CAPTCHA_TIMES = Title.Times.times(Duration.ZERO, Duration.ofMillis(1600), Duration.ZERO);

    private final GroupManager groups;
    private final AfkService afk;
    private PluginConfig config;
    private Messages messages;

    /** target -> invite */
    private final Map<UUID, Invite> invites = new HashMap<>();
    /** target -> captcha */
    private final Map<UUID, Captcha> captchas = new ConcurrentHashMap<>();
    /** sender -> time the cooldown ends */
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public LinkService(GroupManager groups, AfkService afk, PluginConfig config, Messages messages) {
        this.groups = groups;
        this.afk = afk;
        apply(config, messages);
    }

    public void apply(PluginConfig config, Messages messages) {
        this.config = config;
        this.messages = messages;
    }

    public boolean inCaptcha(UUID uuid) {
        return captchas.containsKey(uuid);
    }

    // ---------------------------------------------------------------- /afk link

    public void link(Player sender, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            messages.send(sender, "link-offline", "player", targetName);
            return;
        }
        if (target.equals(sender)) {
            messages.send(sender, "link-self");
            return;
        }
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(sender.getUniqueId());
        if (until != null && until > now) {
            messages.send(sender, "link-cooldown", "seconds", String.valueOf((until - now + 999) / 1000));
            return;
        }
        if (captchas.containsKey(sender.getUniqueId())) {
            messages.send(sender, "link-sender-busy");
            return;
        }
        String reason = checkLinkable(sender, target);
        if (reason != null) {
            sendReason(sender, reason, target.getName());
            return;
        }
        UUID tid = target.getUniqueId();
        if (invites.containsKey(tid) || captchas.containsKey(tid)) {
            messages.send(sender, "link-target-busy", "player", target.getName());
            return;
        }

        invites.put(tid, new Invite(sender.getUniqueId(), sender.getName(), now + config.inviteExpire() * 1000L));
        if (config.linkCooldown() > 0) cooldowns.put(sender.getUniqueId(), now + config.linkCooldown() * 1000L);
        messages.send(sender, "link-sent", "player", target.getName(), "seconds", String.valueOf(config.inviteExpire()));
        messages.sendWithComponents(target, "invite-received", Map.of(
                "accept", button("button-accept", "button-accept-hover", "/afk accept"),
                "deny", button("button-deny", "button-deny-hover", "/afk deny")
        ), "player", sender.getName());
    }

    private Component button(String textKey, String hoverKey, String command) {
        return Messages.colorize(messages.raw(textKey))
                .hoverEvent(HoverEvent.showText(Messages.colorize(messages.raw(hoverKey))))
                .clickEvent(ClickEvent.runCommand(command));
    }

    /** The messages key of the first failed condition, or {@code null} when the two can be linked. */
    private String checkLinkable(Player a, Player b) {
        String hashA = groups.currentHash(a.getUniqueId());
        String hashB = groups.currentHash(b.getUniqueId());
        if (hashA == null || !hashA.equals(hashB)) return "link-different-ip";
        List<UUID> group = groups.groupOfHash(hashA);
        if (group.contains(a.getUniqueId()) && group.contains(b.getUniqueId())) return "link-already";
        if (groups.sizeAfterLink(hashA, a.getUniqueId(), b.getUniqueId()) > config.maxGroupSize()) return "link-full";
        return null;
    }

    private void sendReason(Player to, String key, String otherName) {
        messages.send(to, key, "player", otherName, "max", String.valueOf(config.maxGroupSize()));
    }

    // ---------------------------------------------------------------- accept / deny

    public void accept(Player target) {
        Invite invite = invites.remove(target.getUniqueId());
        if (invite == null) {
            messages.send(target, "no-invite");
            return;
        }
        Player sender = Bukkit.getPlayer(invite.sender());
        if (sender == null) {
            messages.send(target, "link-offline", "player", invite.senderName());
            return;
        }
        if (captchas.containsKey(sender.getUniqueId())) {
            messages.send(target, "link-target-busy", "player", sender.getName());
            return;
        }
        String reason = checkLinkable(sender, target);
        if (reason != null) {
            failBoth(sender, target, reason);
            return;
        }
        int number = ThreadLocalRandom.current().nextInt(config.captchaMin(), config.captchaMax() + 1);
        long expiresAt = System.currentTimeMillis() + config.captchaTime() * 1000L;
        captchas.put(target.getUniqueId(), new Captcha(sender.getUniqueId(), sender.getName(), number, expiresAt));
        messages.send(target, "captcha-start", "seconds", String.valueOf(config.captchaTime()));
        messages.send(sender, "captcha-waiting-sender", "player", target.getName());
        showCaptcha(target, number, config.captchaTime());
    }

    private void failBoth(Player sender, Player target, String reason) {
        messages.send(sender, "link-failed-sender", "player", target.getName());
        sendReason(sender, reason, target.getName());
        sendReason(target, reason, sender.getName());
    }

    public void deny(Player target) {
        Invite invite = invites.remove(target.getUniqueId());
        if (invite == null) {
            messages.send(target, "no-invite");
            return;
        }
        messages.send(target, "invite-denied-target", "player", invite.senderName());
        Player sender = Bukkit.getPlayer(invite.sender());
        if (sender != null) messages.send(sender, "invite-denied-sender", "player", target.getName());
    }

    // ---------------------------------------------------------------- captcha

    /** A chat line of a player in the captcha step (already cancelled by the listener). */
    public void onCaptchaChat(Player target, String message) {
        Captcha c = captchas.get(target.getUniqueId());
        if (c == null) return; // finished or expired between the chat and this tick
        String text = message.trim();
        Player sender = Bukkit.getPlayer(c.sender());

        if (text.equalsIgnoreCase("cancel")) {
            endCaptcha(target);
            messages.send(target, "captcha-cancelled");
            if (sender != null) messages.send(sender, "captcha-cancelled-sender", "player", target.getName());
            return;
        }
        if (!text.equals(String.valueOf(c.number()))) {
            messages.send(target, "captcha-wrong");
            return;
        }

        endCaptcha(target);
        if (sender == null) {
            messages.send(target, "link-offline", "player", c.senderName());
            return;
        }
        // IPs or the group may have changed during the 15 seconds.
        String reason = checkLinkable(sender, target);
        if (reason != null) {
            failBoth(sender, target, reason);
            return;
        }
        groups.link(groups.currentHash(sender.getUniqueId()),
                sender.getUniqueId(), sender.getName(), target.getUniqueId(), target.getName());
        for (Player p : List.of(sender, target)) {
            messages.send(p, "link-success");
            afk.sendGroupLine(p, "group-line");
        }
    }

    private void endCaptcha(Player target) {
        captchas.remove(target.getUniqueId());
        target.clearTitle();
    }

    private void showCaptcha(Player target, int number, long secondsLeft) {
        target.showTitle(Title.title(
                Messages.colorize(Messages.format(messages.raw("captcha-title"), "number", String.valueOf(number))),
                Messages.colorize(Messages.format(messages.raw("captcha-subtitle"), "seconds", String.valueOf(secondsLeft))),
                CAPTCHA_TIMES));
    }

    // ---------------------------------------------------------------- housekeeping

    /** Every second: expiries and the captcha title countdown. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (!invites.isEmpty()) {
            Iterator<Map.Entry<UUID, Invite>> it = invites.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Invite> e = it.next();
                Invite invite = e.getValue();
                if (invite.expiresAt() > now) continue;
                it.remove();
                Player target = Bukkit.getPlayer(e.getKey());
                Player sender = Bukkit.getPlayer(invite.sender());
                if (target != null) messages.send(target, "invite-expired-target", "player", invite.senderName());
                if (sender != null && target != null) {
                    messages.send(sender, "invite-expired-sender", "player", target.getName());
                }
            }
        }
        if (!captchas.isEmpty()) {
            for (Map.Entry<UUID, Captcha> e : List.copyOf(captchas.entrySet())) {
                Captcha c = e.getValue();
                Player target = Bukkit.getPlayer(e.getKey());
                if (target == null) {
                    captchas.remove(e.getKey());
                    continue;
                }
                if (c.expiresAt() <= now) {
                    endCaptcha(target);
                    messages.send(target, "captcha-timeout");
                    Player sender = Bukkit.getPlayer(c.sender());
                    if (sender != null) messages.send(sender, "captcha-timeout-sender", "player", target.getName());
                } else {
                    showCaptcha(target, c.number(), (c.expiresAt() - now + 999) / 1000);
                }
            }
        }
        if (!cooldowns.isEmpty()) cooldowns.values().removeIf(until -> until <= now);
    }

    public void onQuit(UUID uuid) {
        invites.remove(uuid);
        invites.values().removeIf(i -> i.sender().equals(uuid));
        captchas.remove(uuid);
        for (Map.Entry<UUID, Captcha> e : List.copyOf(captchas.entrySet())) {
            if (!e.getValue().sender().equals(uuid)) continue;
            Player target = Bukkit.getPlayer(e.getKey());
            if (target != null) {
                endCaptcha(target);
                messages.send(target, "link-offline", "player", e.getValue().senderName());
            } else {
                captchas.remove(e.getKey());
            }
        }
        cooldowns.remove(uuid);
    }

    public void clear() {
        for (UUID uuid : captchas.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.clearTitle();
        }
        invites.clear();
        captchas.clear();
        cooldowns.clear();
    }
}
