package org;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Windcharge tech, the same rules as the Skript it replaces:
 *
 *   right-click with a wind charge in your hand, and ALL of these are true:
 *     - you are not in creative
 *     - you have fallen at least min-fall-distance blocks (so not just standing on the ground)
 *     - you look at least pitch-threshold degrees down
 *     - the cooldown is over
 *   then the wind charge is NOT thrown, one charge is used up, and you get a small push in the
 *   direction you face (forward-boost sideways, upward-nudge up). Your own speed is replaced by that push.
 *   If a rule is not met the click works like vanilla (a normal throw), also during the cooldown.
 *
 * Settings:   combat.yml -> windcharge-tech   (enabled: false turns it off, /ze reload applies it)
 * Permission: unstableffa.windchargetech (everyone by default)
 * Trouble:    set windcharge-tech.debug: true and it shows above your hotbar which rule stopped it.
 * Works on its own: it only needs the plugin's config.
 */
final class WindchargeTech implements Listener {

    private final zenithEssentials plugin;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    WindchargeTech(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    /** null = every rule is met, otherwise the first rule that is not met. */
    private String blocker(Player p, FileConfiguration cfg) {
        if (!p.hasPermission("unstableffa.windchargetech")) {
            return "you do not have unstableffa.windchargetech";
        }
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            return "not in creative or spectator";
        }
        if (cfg.getStringList("windcharge-tech.disabled-worlds").contains(p.getWorld().getName())) {
            return "disabled in this world";
        }
        double minFall = cfg.getDouble("windcharge-tech.min-fall-distance", 3.0);
        if (p.getFallDistance() < minFall) {
            return String.format(Locale.US, "fall distance %.1f, needs %.1f (fall further)", p.getFallDistance(), minFall);
        }
        double minPitch = cfg.getDouble("windcharge-tech.pitch-threshold", 65.0);
        if (p.getLocation().getPitch() < minPitch) {
            return String.format(Locale.US, "pitch %.0f, needs %.0f (look further down)", p.getLocation().getPitch(), minPitch);
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND || e.useItemInHand() == Event.Result.DENY) {
            return;
        }
        FileConfiguration cfg = this.plugin.getConfig();
        if (!cfg.getBoolean("windcharge-tech.enabled", true)) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool == null || tool.getType() != Material.WIND_CHARGE) {
            return;
        }

        String why = this.blocker(p, cfg);
        if (why != null) {
            if (cfg.getBoolean("windcharge-tech.debug", false)) {
                p.sendActionBar(net.kyori.adventure.text.Component.text("Windcharge tech: " + why));
            }
            return; // a normal throw
        }

        long now = System.currentTimeMillis();
        long cooldownMs = (long) (cfg.getDouble("windcharge-tech.cooldown-seconds", 5.0) * 1000.0);
        Long last = this.lastUse.get(p.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            String msg = cfg.getString("windcharge-tech.cooldown-message", "&#D9381EWindcharge tech is on cooldown!");
            if (msg != null && !msg.isEmpty()) {
                long left = (cooldownMs - (now - last) + 999L) / 1000L;
                p.sendActionBar(net.kyori.adventure.text.Component.text(
                        Announcer.color(msg.replace("{time}", String.valueOf(left)))));
            }
            return; // no tech, and the click works like vanilla
        }

        // the tech: no throw, one charge used, push in the facing direction
        e.setCancelled(true);
        if (tool.getAmount() <= 1) {
            p.getInventory().setItemInMainHand(null);
        } else {
            tool.setAmount(tool.getAmount() - 1);
        }
        this.lastUse.put(p.getUniqueId(), now);

        double yawRad = Math.toRadians(p.getLocation().getYaw());
        double side = cfg.getDouble("windcharge-tech.forward-boost", 0.32);
        double lift = cfg.getDouble("windcharge-tech.upward-nudge", 0.1);
        p.setVelocity(new Vector(-Math.sin(yawRad) * side, lift, Math.cos(yawRad) * side));

        String sound = cfg.getString("windcharge-tech.sound", "ENTITY_WIND_CHARGE_WIND_BURST");
        if (sound != null && !sound.isEmpty()) {
            try {
                p.playSound(p.getLocation(), Sound.valueOf(sound.toUpperCase(Locale.ROOT)), 1.0F, 1.0F); // only the player hears it
            } catch (Exception ignored) {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.lastUse.remove(e.getPlayer().getUniqueId());
    }
}
