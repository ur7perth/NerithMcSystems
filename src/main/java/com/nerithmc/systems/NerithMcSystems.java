package com.nerithmc.systems;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NerithMcSystems extends JavaPlugin implements Listener, CommandExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Title.Times TIMES =
            Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000));

    private final Map<UUID, Long> chatLast = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cmdLast = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[]{"ifplayerkill", "ifplayerdie", "title"}) {
            if (getCommand(name) != null) getCommand(name).setExecutor(this);
        }
        applyCobwebFix();
    }

    private Component color(String text) {
        return LEGACY.deserialize(text);
    }

    private void showTitle(Player p, Component title, Component subtitle) {
        p.showTitle(Title.title(title, subtitle, TIMES));
    }

    // ---------- 1) Cancel damage: ground / wall / ceiling ----------
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        switch (e.getCause()) {
            case FALL, FLY_INTO_WALL, SUFFOCATION -> e.setCancelled(true);
            default -> { }
        }
    }

    // ---------- 2) Kill / die titles (title + subtitle) ----------
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;

        String killTitle = getConfig().getString("kill-title", "&a&lKILL");
        String dieTitle = getConfig().getString("die-title", "&c&lYOU DIED");

        String killText = getConfig().getString("kill-message", "&aYou Killed %Player%")
                .replace("%Player%", victim.getName());
        String dieText = getConfig().getString("die-message", "&cYou were killed by %Player%")
                .replace("%Player%", killer.getName());

        showTitle(killer, color(killTitle), color(killText));
        showTitle(victim, color(dieTitle), color(dieText));
    }

    // ---------- 5) Cooldowns ----------
    private boolean pass(Map<UUID, Long> map, UUID id) {
        long now = System.currentTimeMillis();
        long cd = getConfig().getInt("cooldown-seconds", 3) * 1000L;
        Long prev = map.get(id);
        if (prev != null && now - prev < cd) return false;
        map.put(id, now);
        return true;
    }

    private String seconds() {
        return String.valueOf(getConfig().getInt("cooldown-seconds", 3));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("nerithmcsystems.bypass")) return;
        if (!pass(chatLast, p.getUniqueId())) {
            e.setCancelled(true);
            p.sendMessage(color(getConfig().getString("chat-cooldown-message", "&cPlease wait")
                    .replace("%Seconds%", seconds())));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("nerithmcsystems.bypass")) return;
        if (!pass(cmdLast, p.getUniqueId())) {
            e.setCancelled(true);
            p.sendMessage(color(getConfig().getString("command-cooldown-message", "&cPlease wait")
                    .replace("%Seconds%", seconds())));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        chatLast.remove(e.getPlayer().getUniqueId());
        cmdLast.remove(e.getPlayer().getUniqueId());
    }

    // ---------- Commands ----------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (args.length == 0) {
            if (name.equals("ifplayerkill")) {
                sender.sendMessage("Current: " + getConfig().getString("kill-message"));
            } else if (name.equals("ifplayerdie")) {
                sender.sendMessage("Current: " + getConfig().getString("die-message"));
            } else {
                sender.sendMessage("Usage: /title <message>");
            }
            return true;
        }

        String message = String.join(" ", args);

        switch (name) {
            case "ifplayerkill" -> {
                getConfig().set("kill-message", message);
                saveConfig();
                sender.sendMessage(Component.text("Kill message set: ")
                        .append(color(message.replace("%Player%", "Steve"))));
            }
            case "ifplayerdie" -> {
                getConfig().set("die-message", message);
                saveConfig();
                sender.sendMessage(Component.text("Die message set: ")
                        .append(color(message.replace("%Player%", "Steve"))));
            }
            case "title" -> {
                Component title = color("&a&lAnnouncement");
                Component sub = color("&f" + message);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    showTitle(p, title, sub);
                }
            }
            default -> { return false; }
        }
        return true;
    }

    // ---------- 3) Cobweb fix ----------
    private void applyCobwebFix() {
        double value = getConfig().getDouble("cobweb-fix.moved-wrongly-threshold", 2.0);
        if (value <= 0) return;
        try {
            Class<?> cls = Class.forName("org.spigotmc.SpigotConfig");
            Field f = cls.getField("movedWronglyThreshold");
            f.setDouble(null, value);
            getLogger().info("Cobweb fix applied (moved-wrongly-threshold = " + value + ")");
        } catch (Exception ex) {
            getLogger().warning("Could not apply cobweb fix automatically. "
                    + "Set settings.moved-wrongly-threshold in spigot.yml manually. (" + ex + ")");
        }
    }
}
