package org;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Chat protection for UnstableFFA:
 *  - chat cooldown + "don't repeat the same message"
 *  - command cooldowns (/tpa, /msg, /home ... anything you list in config.yml)
 *  - anti-cuss: censors or blocks swear words (catches f.u.c.k, fuuuck, sh1t, f u c k ...)
 *  - the slur filter (chat-filter.words) that auto-warns
 *
 * Everything here is thread-safe, because chat runs off the main thread.
 * Settings are read once in reload() (called on startup and /unstableffa reload).
 */
public final class ChatGuard {

    /** Result of an anti-cuss check. */
    public static final class CussResult {
        public final String text;      // the message after censoring
        public final boolean blocked;  // true = don't send the message at all
        public final boolean autoWarn; // true = also give the player an official warning

        CussResult(String text, boolean blocked, boolean autoWarn) {
            this.text = text;
            this.blocked = blocked;
            this.autoWarn = autoWarn;
        }
    }

    public static final String PERM_COOLDOWN_BYPASS = "unstableffa.cooldown.bypass";
    public static final String PERM_FLOOD_BYPASS = "unstableffa.flood.bypass";
    public static final String PERM_ANTICUSS_BYPASS = "unstableffa.anticuss.bypass";

    // Our own commands that start their cooldown only when they actually succeed
    // (so a typo in a player name doesn't cost you 30 seconds).
    private static final Set<String> HOOKED = new HashSet<>(java.util.Arrays.asList("tpa", "tpahere", "msg"));
    private static final Set<String> PRIVATE_MESSAGE = new HashSet<>(java.util.Arrays.asList("msg", "message", "tell", "w", "whisper"));

    private final zenithEssentials plugin;

    // state
    private final Map<UUID, Long> lastChat = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastText = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTextTime = new ConcurrentHashMap<>();
    private final Map<String, Long> commandUsed = new ConcurrentHashMap<>(); // "uuid:command" -> millis

    // settings (rebuilt by reload)
    private volatile double chatCooldown = 2.0;
    private volatile int duplicateSeconds = 30;
    private volatile Map<String, Integer> commandCooldowns = Collections.emptyMap();
    private volatile boolean cussEnabled = true;
    private volatile String cussAction = "censor";
    private volatile boolean cussWarnPlayer = true;
    private volatile boolean cussAutoWarn = false;
    private volatile boolean cussInMessages = true;
    private volatile char censorChar = '*';
    private volatile List<Pattern> cussPatterns = Collections.emptyList();
    private volatile Set<String> whitelist = Collections.emptySet();
    private volatile List<String> blockedWords = Collections.emptyList();

    // flood filter
    private volatile boolean floodEnabled = false;
    private volatile boolean floodBlock = false;
    private volatile int floodMaxChars = 4;
    private volatile int floodMaxWords = 3;
    private volatile int floodMaxSymbolRun = 5;
    private volatile int floodSymbolPercent = 60;
    private volatile int floodMaxLength = 200;
    private volatile boolean floodTell = true;
    private volatile int floodStrikeAfter = 0;
    private volatile long floodStrikeWindow = 120000L;
    private final Map<UUID, List<Long>> floodStrikes = new ConcurrentHashMap<>();

    public ChatGuard(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    // =====================================================================================
    // settings
    // =====================================================================================

    public void reload() {
        FileConfiguration c = this.plugin.getConfig();

        this.chatCooldown = Math.max(0.0, c.getDouble("chat-cooldown.seconds", 2.0));
        this.duplicateSeconds = Math.max(0, c.getInt("chat-cooldown.block-repeat-seconds", 30));

        Map<String, Integer> cds = new HashMap<>();
        ConfigurationSection cs = c.getConfigurationSection("command-cooldowns");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                int v = cs.getInt(k, 0);
                if (v > 0) {
                    cds.put(k.toLowerCase(Locale.ROOT), v);
                }
            }
        }
        this.commandCooldowns = cds;

        this.cussEnabled = c.getBoolean("anti-cuss.enabled", true);
        this.cussAction = c.getString("anti-cuss.action", "censor").toLowerCase(Locale.ROOT);
        this.cussWarnPlayer = c.getBoolean("anti-cuss.tell-player", true);
        this.cussAutoWarn = c.getBoolean("anti-cuss.auto-warn", false);
        this.cussInMessages = c.getBoolean("anti-cuss.check-private-messages", true);
        String cc = c.getString("anti-cuss.censor-char", "*");
        this.censorChar = cc == null || cc.isEmpty() ? '*' : cc.charAt(0);

        Set<String> wl = new HashSet<>();
        for (String w : c.getStringList("anti-cuss.whitelist")) {
            wl.add(w.toLowerCase(Locale.ROOT).trim());
        }
        this.whitelist = wl;

        List<Pattern> pats = new ArrayList<>();
        for (String w : c.getStringList("anti-cuss.words")) {
            Pattern p = this.buildPattern(w);
            if (p != null) {
                pats.add(p);
            }
        }
        this.cussPatterns = pats;

        List<String> bw = new ArrayList<>();
        for (String w : c.getStringList("chat-filter.words")) {
            if (w != null && !w.trim().isEmpty()) {
                bw.add(w.toLowerCase(Locale.ROOT).trim());
            }
        }
        this.blockedWords = bw;

        this.floodEnabled = c.getBoolean("chat-flood.enabled", false);
        this.floodBlock = c.getString("chat-flood.action", "lower").equalsIgnoreCase("block");
        this.floodMaxChars = Math.max(1, c.getInt("chat-flood.max-repeated-chars", 4));
        this.floodMaxWords = Math.max(1, c.getInt("chat-flood.max-repeated-words", 3));
        this.floodMaxSymbolRun = Math.max(1, c.getInt("chat-flood.max-symbol-run", 5));
        this.floodSymbolPercent = Math.max(0, c.getInt("chat-flood.max-symbol-percent", 60));
        this.floodMaxLength = Math.max(0, c.getInt("chat-flood.max-length", 200));
        this.floodTell = c.getBoolean("chat-flood.tell-player", true);
        this.floodStrikeAfter = Math.max(0, c.getInt("chat-flood.strikes.after", 0));
        this.floodStrikeWindow = Math.max(1, c.getInt("chat-flood.strikes.within-minutes", 2)) * 60000L;
    }

    // =====================================================================================
    // anti-evasion: turns look-alike / invisible characters into plain letters.
    // The result is ALWAYS the same length as the input, so positions still line up
    // with the original message (invisible characters become \u0000, which the patterns skip).
    // =====================================================================================

    private static final String LOOK_FROM = "\u0430\u0435\u043e\u0440\u0441\u0443\u0445\u0456\u0458\u0455\u0501\u04bb\u043a\u0432\u043d\u0442\u043c\u0457\u0261\u0192\u03b1\u03b5\u03b9\u03ba\u03bf\u03c1\u03c5\u03bd\u03c4\u03b2\u03b7\u03bc\u03c9\u0131\u0251\u0254\u0261\u2113\u00f8\u0153\u00e6";
    private static final String LOOK_TO   = "aeopcyxijsdhkbhtmigfaeikopuvtbnmwiaoglooa";

    static String fold(String in) {
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); ) {
            int cp = in.codePointAt(i);
            int n = Character.charCount(cp);
            sb.append(foldCp(cp));
            for (int k = 1; k < n; k++) {
                sb.append('\u0000');
            }
            i += n;
        }
        return sb.toString();
    }

    private static char foldCp(int cp) {
        if (cp < 128) {
            return Character.toLowerCase((char) cp);
        }
        int type = Character.getType(cp);
        if (type == Character.FORMAT || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.CONTROL || cp == 0x00AD || cp == 0x3164 || cp == 0x2800) {
            return '\u0000';
        }
        if (cp <= 0xFFFF) {
            int idx = LOOK_FROM.indexOf(Character.toLowerCase((char) cp));
            if (idx >= 0 && idx < LOOK_TO.length()) {
                return LOOK_TO.charAt(idx);
            }
        }
        // fullwidth, bold/italic maths letters, circled letters, accents ...
        String d = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKD);
        for (int j = 0; j < d.length(); j++) {
            char ch = d.charAt(j);
            if (ch < 128 && Character.isLetterOrDigit(ch)) {
                return Character.toLowerCase(ch);
            }
        }
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return ' ';
        }
        return cp <= 0xFFFF ? Character.toLowerCase((char) cp) : ' ';
    }

    // =====================================================================================
    // flood filter
    // =====================================================================================

    /** Result of a flood check. */
    public static final class FloodResult {
        public final String text;     // cleaned-up message (when not blocked)
        public final boolean blocked; // true = don't send
        public final boolean strike;  // true = player hit the strike limit

        FloodResult(String text, boolean blocked, boolean strike) {
            this.text = text;
            this.blocked = blocked;
            this.strike = strike;
        }
    }

    private static final Pattern SYMBOL_RUN_CHECK = Pattern.compile("[^\\p{L}\\p{N}\\s]");

    /** @return null if the message is fine (or the player bypasses). */
    public FloodResult applyFlood(Player p, String message) {
        if (!this.floodEnabled || p.hasPermission(PERM_FLOOD_BYPASS)) {
            return null;
        }
        boolean bad = false;
        boolean tooLong = this.floodMaxLength > 0 && message.length() > this.floodMaxLength;
        String text = message;

        // aaaaaaa -> aaaa
        String t2 = collapseChars(text, this.floodMaxChars);
        if (!t2.equals(text)) {
            bad = true;
            text = t2;
        }

        // lol lol lol lol -> lol lol lol
        String[] words = text.split(" ");
        StringBuilder sb = new StringBuilder();
        int run = 0;
        String prev = null;
        boolean changedWords = false;
        for (String w : words) {
            String key = w.toLowerCase(Locale.ROOT);
            if (prev != null && key.equals(prev) && !key.isEmpty()) {
                run++;
            } else {
                run = 1;
            }
            prev = key;
            if (run > this.floodMaxWords) {
                changedWords = true;
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(w);
        }
        if (changedWords) {
            bad = true;
            text = sb.toString();
        }

        // !!!!!!!!! / ??????? / mostly symbols
        int symbols = 0;
        int cur = 0;
        int maxRun = 0;
        for (int i = 0; i < text.length(); i++) {
            if (SYMBOL_RUN_CHECK.matcher(String.valueOf(text.charAt(i))).matches()) {
                symbols++;
                cur++;
                maxRun = Math.max(maxRun, cur);
            } else {
                cur = 0;
            }
        }
        boolean symbolSpam = maxRun > this.floodMaxSymbolRun
                || (text.length() >= 8 && this.floodSymbolPercent > 0 && symbols * 100 / text.length() >= this.floodSymbolPercent);
        if (symbolSpam) {
            bad = true;
            // cut every symbol run down to the limit
            text = collapseSymbols(text, this.floodMaxSymbolRun);
            if (text.length() >= 8 && this.floodSymbolPercent > 0) {
                int s2 = 0;
                for (int i = 0; i < text.length(); i++) {
                    if (SYMBOL_RUN_CHECK.matcher(String.valueOf(text.charAt(i))).matches()) {
                        s2++;
                    }
                }
                if (s2 * 100 / text.length() >= this.floodSymbolPercent) {
                    tooLong = true; // still mostly symbols -> block
                }
            }
        }

        if (!bad && !tooLong) {
            return null;
        }
        boolean block = this.floodBlock || tooLong || text.trim().isEmpty();
        return new FloodResult(text, block, this.addStrike(p));
    }

    private boolean addStrike(Player p) {
        if (this.floodStrikeAfter <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        List<Long> l = this.floodStrikes.computeIfAbsent(p.getUniqueId(), k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (l) {
            l.removeIf(t -> now - t > this.floodStrikeWindow);
            l.add(now);
            if (l.size() >= this.floodStrikeAfter) {
                l.clear();
                return true;
            }
        }
        return false;
    }

    private static String collapseChars(String s, int max) {
        StringBuilder sb = new StringBuilder(s.length());
        int count = 0;
        int prev = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (prev != -1 && Character.toLowerCase(c) == prev) {
                count++;
            } else {
                count = 1;
            }
            prev = Character.toLowerCase(c);
            if (count <= max) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String collapseSymbols(String s, int max) {
        StringBuilder sb = new StringBuilder(s.length());
        int cur = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (SYMBOL_RUN_CHECK.matcher(String.valueOf(c)).matches()) {
                cur++;
                if (cur > max) {
                    continue;
                }
            } else {
                cur = 0;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public String floodNotice(boolean blocked) {
        if (!this.floodTell) {
            return null;
        }
        return blocked
                ? this.plugin.fmt("flood-blocked", "&#D9381EYour message wasn't sent - please don't spam.", Collections.emptyMap())
                : this.plugin.fmt("flood-trimmed", "&7Your message was shortened - please don't spam.", Collections.emptyMap());
    }

    // Lookalike characters people use to dodge filters.
    private static String charClass(char ch) {
        switch (ch) {
            case 'a':
                return "[a4@]";
            case 'b':
                return "[b8]";
            case 'e':
                return "[e3]";
            case 'g':
                return "[g9]";
            case 'i':
                return "[i1!|]";
            case 'l':
                return "[l1|]";
            case 'o':
                return "[o0]";
            case 's':
                return "[s5$z]";
            case 't':
                return "[t7+]";
            case 'u':
                return "[uv]";
            default:
                return Pattern.quote(String.valueOf(ch));
        }
    }

    // "fuck" -> matches fuck, FUCK, fuuuck, f.u.c.k, f u c k, phuck is NOT included (add it to the list),
    // plus common endings (fucking, fucker, fucked, shitty, ...). Never matches inside another word
    // (so "class", "assist", "scunthorpe" are safe).
    private Pattern buildPattern(String word) {
        if (word == null) {
            return null;
        }
        String w = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (w.isEmpty()) {
            return null;
        }
        StringBuilder re = new StringBuilder("(?<![a-z0-9])");
        for (int i = 0; i < w.length(); i++) {
            if (i > 0) {
                re.append("[\\s._\\-*,\\u0000]*");
            }
            re.append(charClass(w.charAt(i))).append('+');
        }
        re.append("(?:s|es|er|ers|ing|in|ed|y|ty|head|face|hole)?(?![a-z0-9])");
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    // =====================================================================================
    // chat cooldown / repeat protection
    // =====================================================================================

    /** @return null if the player may send this chat message, otherwise the message to show them. */
    public String checkChat(Player p, String message) {
        if (p.hasPermission(PERM_COOLDOWN_BYPASS)) {
            return null;
        }
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = this.lastChat.get(id);
        long cdMs = (long) (this.chatCooldown * 1000.0);
        if (last != null && cdMs > 0 && now - last < cdMs) {
            return this.plugin.fmt("chat-cooldown", "&#D9381ESlow down! You can chat again in &f{time}&#D9381E.",
                    Collections.singletonMap("time", formatTime(cdMs - (now - last))));
        }

        String norm = fold(message).replaceAll("[^a-z0-9]", "");
        String prev = this.lastText.get(id);
        Long prevTime = this.lastTextTime.get(id);
        if (this.duplicateSeconds > 0 && prev != null && prevTime != null && !norm.isEmpty()
                && prev.equals(norm) && now - prevTime < this.duplicateSeconds * 1000L) {
            return this.plugin.fmt("chat-repeat", "&#D9381EPlease don't send the same message twice.", Collections.emptyMap());
        }

        this.lastChat.put(id, now);
        this.lastText.put(id, norm);
        this.lastTextTime.put(id, now);
        return null;
    }

    // =====================================================================================
    // command cooldowns
    // =====================================================================================

    /** Turns what the player typed (an alias, "minecraft:tell", ...) into the config key. */
    public String commandKey(String typed) {
        String base = typed.toLowerCase(Locale.ROOT);
        int colon = base.indexOf(':');
        if (colon != -1) {
            base = base.substring(colon + 1);
        }
        PluginCommand pc = Bukkit.getPluginCommand(base);
        if (pc != null && pc.getPlugin() == this.plugin) {
            base = pc.getName().toLowerCase(Locale.ROOT);
        }
        if (PRIVATE_MESSAGE.contains(base)) {
            return "msg";
        }
        return base;
    }

    private boolean isOurs(String typed) {
        String base = typed.toLowerCase(Locale.ROOT);
        int colon = base.indexOf(':');
        if (colon != -1) {
            base = base.substring(colon + 1);
        }
        PluginCommand pc = Bukkit.getPluginCommand(base);
        return pc != null && pc.getPlugin() == this.plugin;
    }

    /**
     * Called for every command a player types.
     * @return null if allowed, otherwise the "please wait" message.
     */
    public String checkCommand(Player p, String typed) {
        String key = this.commandKey(typed);
        Integer cd = this.commandCooldowns.get(key);
        if (cd == null || cd <= 0 || p.hasPermission(PERM_COOLDOWN_BYPASS)) {
            return null;
        }
        long left = this.cooldownLeft(p, key, cd);
        if (left > 0) {
            Map<String, String> ph = new HashMap<>();
            ph.put("time", formatTime(left));
            ph.put("command", key);
            return this.plugin.fmt("command-cooldown", "&#D9381EPlease wait &f{time} &#D9381Ebefore using &f/{command} &#D9381Eagain.", ph);
        }
        // Commands we hook ourselves only start their cooldown when they succeed.
        if (!(HOOKED.contains(key) && this.isOurs(typed))) {
            this.markUsed(p, key);
        }
        return null;
    }

    public long cooldownLeft(Player p, String key, int seconds) {
        Long used = this.commandUsed.get(p.getUniqueId() + ":" + key);
        if (used == null) {
            return 0L;
        }
        return Math.max(0L, used + seconds * 1000L - System.currentTimeMillis());
    }

    /** Start the cooldown for this command (only does something if it has one in config). */
    public void markUsed(Player p, String key) {
        if (this.commandCooldowns.containsKey(key) && !p.hasPermission(PERM_COOLDOWN_BYPASS)) {
            this.commandUsed.put(p.getUniqueId() + ":" + key, System.currentTimeMillis());
        }
    }

    /** Frees chat memory when a player leaves (command cooldowns survive a relog on purpose). */
    public void forget(UUID id) {
        this.lastChat.remove(id);
        this.lastText.remove(id);
        this.lastTextTime.remove(id);
        long now = System.currentTimeMillis();
        this.commandUsed.values().removeIf(t -> now - t > 3600000L); // drop anything older than 1h
    }

    // =====================================================================================
    // anti-cuss
    // =====================================================================================

    public boolean checksPrivateMessages() {
        return this.cussInMessages;
    }

    /**
     * Censors (or blocks) swear words.
     * @return null if the message is clean or the player can bypass, otherwise what to do with it.
     */
    public CussResult applyAntiCuss(Player p, String message) {
        if (!this.cussEnabled || this.cussPatterns.isEmpty() || p.hasPermission(PERM_ANTICUSS_BYPASS)) {
            return null;
        }
        char[] out = message.toCharArray();
        String folded = fold(message);
        boolean hit = false;
        for (Pattern pat : this.cussPatterns) {
            Matcher m = pat.matcher(folded);
            while (m.find()) {
                String found = m.group().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (this.whitelist.contains(found)) {
                    continue;
                }
                hit = true;
                for (int i = m.start(); i < m.end(); i++) {
                    if (!Character.isWhitespace(out[i]) && i < out.length) {
                        out[i] = this.censorChar;
                    }
                }
            }
        }
        if (!hit) {
            return null;
        }
        return new CussResult(new String(out), this.cussAction.equals("block"), this.cussAutoWarn);
    }

    /** The message to tell a player whose message was censored/blocked (or null if turned off). */
    public String cussNotice(boolean blocked) {
        if (!this.cussWarnPlayer) {
            return null;
        }
        return blocked
                ? this.plugin.fmt("anticuss-blocked", "&#D9381EYour message wasn't sent - please keep the chat clean.", Collections.emptyMap())
                : this.plugin.fmt("anticuss-censored", "&7Watch your language - part of your message was censored.", Collections.emptyMap());
    }

    // =====================================================================================
    // slur filter (chat-filter.words): these auto-warn and are never sent
    // =====================================================================================

    public boolean containsBlockedWord(String msg) {
        if (this.blockedWords.isEmpty()) {
            return false;
        }
        String norm = fold(msg).replace("\u0000", "").replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a')
                .replace('5', 's').replace('@', 'a').replace('$', 's');
        String spaced = " " + norm.replaceAll("[^a-z]", " ") + " ";
        for (String w : this.blockedWords) {
            if (spaced.contains(" " + w + " ")) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================================

    private static String formatTime(long ms) {
        long sec = (ms + 999L) / 1000L;
        if (sec < 60) {
            return sec + "s";
        }
        long min = sec / 60;
        sec = sec % 60;
        if (min < 60) {
            return min + "m" + (sec > 0 ? " " + sec + "s" : "");
        }
        return (min / 60) + "h " + (min % 60) + "m";
    }
}
