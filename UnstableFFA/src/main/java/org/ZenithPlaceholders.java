package org;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI expansion. Only loaded when PlaceholderAPI is installed (see the hook in onEnable).
 * Works in TAB, scoreboards, chat formats, holograms, anything that uses PlaceholderAPI.
 *
 * Stats
 *   %zenith_killstreak%  %zenith_best_killstreak%  %zenith_kills%  %zenith_deaths%  %zenith_kd%
 *   %zenith_mob_kills%  %zenith_hits%  %zenith_swings%  %zenith_accuracy%
 *   %zenith_playtime% (2d 3h 10m)  %zenith_playtime_hours%
 * Player
 *   %zenith_nick%  %zenith_first_join%  %zenith_last_seen%
 *   %zenith_staff%  %zenith_vanished%  %zenith_modmode%  %zenith_frozen%  %zenith_muted%
 *   %zenith_warns% (active)  %zenith_bans%  %zenith_mutes%  %zenith_kicks%
 *   %zenith_combat% (true/false)  %zenith_combat_time% (seconds left)
 * Server
 *   %zenith_online% (without vanished staff)  %zenith_staff_online%  %zenith_max_players%  %zenith_tps%
 * Leaderboards (categories = keys in top.categories: kills, deaths, killstreak, playtime)
 *   %zenith_rank_kills%                    your position, "-" if not ranked
 *   %zenith_top_kills_1_name%              name of number 1
 *   %zenith_top_kills_1_value%             their value, formatted like the menu
 */
final class ZenithPlaceholders extends PlaceholderExpansion {

    private static ZenithPlaceholders instance;

    private final zenithEssentials plugin;

    private ZenithPlaceholders(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    static void hook(zenithEssentials plugin) {
        if (instance == null) {
            instance = new ZenithPlaceholders(plugin);
            instance.register();
            plugin.startPapiBoards();
        }
    }

    static void unhook() {
        if (instance != null) {
            instance.unregister();
            instance = null;
        }
    }

    @Override
    public String getIdentifier() {
        return "zenith";
    }

    @Override
    public String getAuthor() {
        return "DY76";
    }

    @Override
    public String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return this.plugin.placeholder(player, params); // null = unknown placeholder, PlaceholderAPI leaves the text as it is
    }
}
