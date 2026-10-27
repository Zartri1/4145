package org;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

// One open plugin menu. The inventory is re-rendered in place when you page / filter / sort.
final class Gui implements InventoryHolder {
    final String type; // seen | profile | history | recent | settings
    Gui parent;        // where the Back button goes
    UUID target;
    String targetName = "";
    int page = 0;
    int filter = 0;
    int sort = 0;
    String search = "";
    String key = "";   // kit name for the kit menus
    Inventory inv;
    final Object[] slotData = new Object[54]; // what each slot means (uuid, log index, action key...)

    Gui(String type) {
        this.type = type;
    }

    @Override
    public Inventory getInventory() {
        return this.inv;
    }
}
