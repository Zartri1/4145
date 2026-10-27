package org;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

/**
 * Zips data.yml (and the other plugin files) into plugins/UnstableFFA/backups/.
 * Automatic every X hours + on startup, or by hand with /ze backup.
 * Settings: config.yml -> backups
 */
public final class BackupManager {

    private final zenithEssentials plugin;
    private BukkitTask task;

    public BackupManager(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    public File folder() {
        return new File(this.plugin.getDataFolder(), "backups");
    }

    public void reload() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("backups.enabled", true)) {
            return;
        }
        long hours = Math.max(1L, c.getLong("backups.interval-hours", 24L));
        long ticks = hours * 60L * 60L * 20L;
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> this.backup(null), ticks, ticks);
    }

    public void startup() {
        FileConfiguration c = this.plugin.getConfig();
        if (c.getBoolean("backups.enabled", true) && c.getBoolean("backups.on-startup", true)) {
            this.backup(null);
        }
    }

    /** Makes a backup right now. Runs on the main thread (the files are small). */
    public void backup(CommandSender who) {
        this.plugin.saveModules();
        this.plugin.saveDataNow(); // make sure the file on disk is up to date
        File dir = this.folder();
        if (!dir.exists() && !dir.mkdirs()) {
            this.plugin.getLogger().warning("Could not create the backups folder.");
            return;
        }
        List<String> include = this.plugin.getConfig().getStringList("backups.include");
        if (include.isEmpty()) {
            include = Arrays.asList("data.yml", "kits.yml", "quests.yml", "staffactivity.yml", "config.yml", "messages.yml");
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File zip = new File(dir, "backup-" + stamp + ".zip");
        int files = 0;
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String name : include) {
                File f = new File(this.plugin.getDataFolder(), name);
                if (f.isFile()) {
                    files += this.add(out, f, name);
                } else if (f.isDirectory()) {
                    files += this.addDir(out, f, name);
                }
            }
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Backup failed: " + ex.getMessage());
            if (who != null) {
                this.plugin.error(who, "Backup failed: " + ex.getMessage());
            }
            return;
        }
        this.cleanup();
        String msg = "Backup saved: backups/" + zip.getName() + " (" + files + " files, " + (zip.length() / 1024L) + " KB)";
        this.plugin.getLogger().info(msg);
        if (who != null) {
            this.plugin.success(who, "" + msg);
        }
    }

    private int add(ZipOutputStream out, File f, String entry) throws IOException {
        out.putNextEntry(new ZipEntry(entry));
        Files.copy(f.toPath(), out);
        out.closeEntry();
        return 1;
    }

    private int addDir(ZipOutputStream out, File dir, String prefix) throws IOException {
        int n = 0;
        File[] list = dir.listFiles();
        if (list == null) {
            return 0;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                n += this.addDir(out, f, prefix + "/" + f.getName());
            } else {
                n += this.add(out, f, prefix + "/" + f.getName());
            }
        }
        return n;
    }

    // Keeps only the newest "keep" backups.
    private void cleanup() {
        int keep = Math.max(1, this.plugin.getConfig().getInt("backups.keep", 14));
        File[] list = this.folder().listFiles((d, name) -> name.startsWith("backup-") && name.endsWith(".zip"));
        if (list == null || list.length <= keep) {
            return;
        }
        List<File> files = new ArrayList<>(Arrays.asList(list));
        files.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int i = keep; i < files.size(); i++) {
            if (!files.get(i).delete()) {
                this.plugin.getLogger().warning("Could not delete old backup " + files.get(i).getName());
            }
        }
    }
}
