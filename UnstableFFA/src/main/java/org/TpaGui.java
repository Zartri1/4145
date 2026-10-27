package org;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * /tpaccept menu  -  when it is turned on, /tpaccept does not accept right away. It opens a small menu
 * with the head of the player who asked in the middle, Accept on the left and Deny on the right.
 * Closing the menu keeps the request, so /tpaccept still works later.
 *
 * Settings: features.yml -> tpa-gui   (enabled: false by default)
 * /tpaccept with a name or when nobody asked works as before.
 */
public final class TpaGui implements Listener {

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        UUID requester;

        @Override
        public Inventory getInventory() {
            return this.inv;
        }
    }

    private final zenithEssentials plugin;

    TpaGui(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        return this.plugin.getConfig().getConfigurationSection("tpa-gui");
    }

    private String str(String key, String def) {
        ConfigurationSection c = this.cfg();
        return c == null ? def : c.getString(key, def);
    }

    boolean enabled() {
        ConfigurationSection c = this.cfg();
        return c != null && c.getBoolean("enabled", false);
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Announcer.color(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> l = new ArrayList<>();
                for (String x : lore) {
                    l.add(Announcer.color(x));
                }
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private Material material(String key, String def) {
        Material m = Material.matchMaterial(this.str(key, def));
        return m == null ? Material.matchMaterial(def) : m;
    }

    void open(Player p, Player requester, boolean here) {
        Holder h = new Holder();
        h.requester = requester.getUniqueId();
        h.inv = Bukkit.createInventory(h, 27, Announcer.color(this.str("title", "&8Teleport request")));
        ItemStack pane = this.item(Material.matchMaterial(this.str("filler", "BLACK_STAINED_GLASS_PANE")) == null
                ? Material.BLACK_STAINED_GLASS_PANE : Material.matchMaterial(this.str("filler", "BLACK_STAINED_GLASS_PANE")), " ", null);
        for (int i = 0; i < 27; i++) {
            h.inv.setItem(i, pane);
        }

        String line = here
                ? this.str("here-line", "&7wants &fyou&7 to teleport to &fthem")
                : this.str("to-line", "&7wants to teleport to &fyou");
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = head.getItemMeta();
        if (m instanceof SkullMeta) {
            ((SkullMeta) m).setOwningPlayer(requester);
        }
        if (m != null) {
            m.setDisplayName(Announcer.color(this.str("head-name", "&#B15CFF&l{player}").replace("{player}", requester.getName())));
            List<String> lore = new ArrayList<>();
            lore.add(Announcer.color(line));
            m.setLore(lore);
            head.setItemMeta(m);
        }
        h.inv.setItem(13, head);
        h.inv.setItem(11, this.item(this.material("accept-icon", "LIME_CONCRETE"), this.str("accept-name", "<gradient:#FF7518:#FFFFFF>&lACC</gradient><gradient:#FFFFFF:#FF7518>&lEPT</gradient>"),
                List.of(this.str("accept-lore", "&7Click to accept."))));
        h.inv.setItem(15, this.item(this.material("deny-icon", "RED_CONCRETE"), this.str("deny-name", "<gradient:#FF7518:#FFFFFF>&lDE</gradient><gradient:#FFFFFF:#FF7518>&lNY</gradient>"),
                List.of(this.str("deny-lore", "&7Click to deny."))));
        p.openInventory(h.inv);
        this.sound(p, "open-sound", "BLOCK_NOTE_BLOCK_PLING");
    }

    private void sound(Player p, String key, String def) {
        String name = this.str(key, def);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase("none")) {
            return;
        }
        try {
            p.playSound(p.getLocation(), Sound.valueOf(name.toUpperCase().replace('.', '_')), 0.7f, 1.2f);
        } catch (Throwable ignored) {
            // unknown sound name in the config
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player) || e.getClickedInventory() != e.getInventory()) {
            return;
        }
        Player p = (Player) e.getWhoClicked();
        Holder h = (Holder) e.getInventory().getHolder();
        int slot = e.getRawSlot();
        if (slot != 11 && slot != 15) {
            return;
        }
        p.closeInventory();
        // the request may have been replaced or cancelled while the menu was open
        UUID now = this.plugin.tpaPendingFrom(p.getUniqueId());
        if (now == null || !now.equals(h.requester)) {
            p.sendMessage(Announcer.color(this.str("expired-message", "&#D9381EThat request is no longer there.")));
            return;
        }
        if (slot == 11) {
            this.sound(p, "accept-sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
            this.plugin.acceptTpa(p);
        } else {
            this.sound(p, "deny-sound", "BLOCK_NOTE_BLOCK_BASS");
            this.plugin.denyTpa(p);
        }
    }
}
