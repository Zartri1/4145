package org;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * /unstableffa config  -  view and change config.yml in-game.
 *
 *   /ze config                         list the top sections
 *   /ze config <path>                  show a value, or the keys inside a section
 *   /ze config <path> <value>          change a value (true/false, numbers, text)
 *   /ze config <path> add <value>      add to a list   (e.g. anti-cuss.words)
 *   /ze config <path> remove <value>   remove from a list
 *
 * Every change is saved to config.yml and the plugin reloads straight away.
 * Permission: unstableffa.config (OP only by default, NOT part of the admin group,
 * because it controls everything - including who the owners are).
 */
public final class ConfigCommand {

    public static final String PERMISSION = "unstableffa.config";

    private final zenithEssentials plugin;

    public ConfigCommand(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    public void handle(CommandSender s, String[] args) {
        // args[0] is "config"
        if (!s.hasPermission(PERMISSION)) {
            s.sendMessage(this.plugin.fmt("no-permission-config", "&#D9381EOnly owners can change the config.", java.util.Collections.emptyMap()));
            return;
        }
        FileConfiguration c = this.plugin.getConfig();

        if (args.length == 1) {
            s.sendMessage("§b§lCONFIG §7(click a section, or /ze config <path> <value>)");
            for (String k : c.getKeys(false)) {
                this.line(s, k, c);
            }
            return;
        }

        String path = args[1];
        if (!c.contains(path)) {
            this.plugin.error(s, "There is no setting called §f" + path + "§7. §7Use §f/ze config §7to browse.");
            return;
        }

        if (args.length == 2) {
            if (c.isConfigurationSection(path)) {
                s.sendMessage("§b§l" + path + " §7(section)");
                ConfigurationSection sec = c.getConfigurationSection(path);
                for (String k : sec.getKeys(false)) {
                    this.line(s, path + "." + k, c);
                }
            } else if (c.isList(path)) {
                List<?> list = c.getList(path);
                s.sendMessage("§b§l" + path + " §7(list, " + list.size() + " entries)");
                for (Object o : list) {
                    this.plugin.info(s, "- §f" + o);
                }
                s.sendMessage("§8/ze config " + path + " add <value>  |  remove <value>");
            } else {
                s.sendMessage("§b" + path + " §8= §f" + c.get(path));
                s.sendMessage("§8/ze config " + path + " <new value>");
            }
            return;
        }

        if (c.isConfigurationSection(path)) {
            this.plugin.error(s, "" + path + " is a section - pick one of the settings inside it.");
            return;
        }

        // ----- lists: add / remove -----
        if (c.isList(path)) {
            String mode = args[2].toLowerCase(Locale.ROOT);
            if (args.length < 4 || !(mode.equals("add") || mode.equals("remove"))) {
                this.plugin.usage(s, "/ze config " + path + " <add|remove> <value>");
                return;
            }
            String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            List<Object> list = new ArrayList<>(c.getList(path));
            if (mode.equals("add")) {
                list.add(this.parse(value));
            } else {
                boolean removed = list.removeIf(o -> String.valueOf(o).equalsIgnoreCase(value));
                if (!removed) {
                    this.plugin.error(s, "" + value + " isn't in that list.");
                    return;
                }
            }
            c.set(path, list);
            this.saveAndReload(s, path, list.toString());
            return;
        }

        // ----- single values -----
        String raw = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        Object old = c.get(path);
        Object value;
        if (old instanceof Boolean) {
            if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
                this.plugin.error(s, "" + path + " must be §ftrue §7or §ffalse§7.");
                return;
            }
            value = Boolean.parseBoolean(raw);
        } else if (old instanceof Integer || old instanceof Long) {
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException ex) {
                this.plugin.error(s, "" + path + " must be a whole number.");
                return;
            }
        } else if (old instanceof Double || old instanceof Float) {
            try {
                value = Double.parseDouble(raw);
            } catch (NumberFormatException ex) {
                this.plugin.error(s, "" + path + " must be a number.");
                return;
            }
        } else {
            value = raw;
        }
        c.set(path, value);
        this.saveAndReload(s, path, String.valueOf(value));
    }

    private void saveAndReload(CommandSender s, String path, String shown) {
        this.plugin.saveConfig();
        this.plugin.reloadAll();
        this.plugin.success(s, "" + path + " §7is now §f" + shown + " §8(saved & reloaded)");
        this.plugin.getLogger().info(s.getName() + " changed config " + path + " to " + shown);
    }

    // true/false and numbers become real booleans/numbers; everything else stays text.
    private Object parse(String v) {
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(v);
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ignored) {
        }
        return v;
    }

    // One clickable line: sections open when clicked, values get suggested for editing.
    private void line(CommandSender s, String path, FileConfiguration c) {
        String text;
        String click;
        ClickEvent.Action action;
        if (c.isConfigurationSection(path)) {
            text = "§7» §b" + path + " §8(section)";
            click = "/ze config " + path;
            action = ClickEvent.Action.RUN_COMMAND;
        } else if (c.isList(path)) {
            text = "§7» §f" + path + " §8= §7list (" + c.getList(path).size() + ")";
            click = "/ze config " + path;
            action = ClickEvent.Action.RUN_COMMAND;
        } else {
            String v = String.valueOf(c.get(path));
            if (v.length() > 40) {
                v = v.substring(0, 40) + "...";
            }
            text = "§7» §f" + path + " §8= §e" + v;
            click = "/ze config " + path + " ";
            action = ClickEvent.Action.SUGGEST_COMMAND;
        }
        if (s instanceof Player) {
            TextComponent tc = new TextComponent(TextComponent.fromLegacyText(text));
            tc.setClickEvent(new ClickEvent(action, click));
            ((Player) s).spigot().sendMessage(tc);
        } else {
            s.sendMessage(text);
        }
    }

    // ----- tab completion -----

    public List<String> complete(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission(PERMISSION)) {
            return out;
        }
        FileConfiguration c = this.plugin.getConfig();
        if (args.length == 2) {
            out.addAll(c.getKeys(true));
        } else if (args.length == 3 && c.contains(args[1])) {
            if (c.isList(args[1])) {
                out.add("add");
                out.add("remove");
            } else if (c.isBoolean(args[1])) {
                out.add("true");
                out.add("false");
            } else if (!c.isConfigurationSection(args[1])) {
                out.add(String.valueOf(c.get(args[1])));
            }
        } else if (args.length == 4 && c.isList(args[1]) && args[2].equalsIgnoreCase("remove")) {
            for (Object o : c.getList(args[1])) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
