package nl.knokko.resourcepack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class ResourcePackPlugin extends JavaPlugin implements Listener {

    private ResourcePackState state;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        this.state = new ResourcePackState(this.getDataFolder());

        // Sync every 25 minutes
        int syncPeriod = 20 * 60 * 25;
        Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this, () -> this.state.sync(Bukkit.getConsoleSender()), syncPeriod, syncPeriod
        );

        // Some actions of the resource pack state must happen on the Bukkit thread
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this.state::updateBukkitThreadTasks, 20, 20);
    }

    @Override
    public void onDisable() {
        this.state.stop();
    }

    @EventHandler
    public void sendResourcePackOnPlayerJoin(PlayerJoinEvent event) {
        synchronized (this.state) {
            String resourcePackUrl = this.state.getCurrentResourcePackUrl();
            byte[] sha1 = this.state.getBinarySha1Hash();
            if (resourcePackUrl != null && sha1 != null) {
                event.getPlayer().setResourcePack(resourcePackUrl, sha1);
            } else {
                event.getPlayer().sendMessage(ChatColor.YELLOW + "The server resource pack is not yet ready.");
            }
        }
    }

    @EventHandler
    public void forceResourcePack(PlayerResourcePackStatusEvent event) {
        FileConfiguration config = this.getConfig();

        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED) {
            if (config.getBoolean("kick-upon-reject")) {
                event.getPlayer().kickPlayer(config.getString("force-reject-message"));
            } else {
                String message = config.getString("optional-reject-message");
                if (message != null && !message.isEmpty()) {
                    event.getPlayer().sendMessage(message);
                }
            }
        }

        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            if (config.getBoolean("kick-upon-failed-download")) {
                event.getPlayer().kickPlayer(config.getString("force-failed-message"));
            } else {
                String message = config.getString("optional-failed-message");
                if (message != null && !message.isEmpty()) {
                    event.getPlayer().sendMessage(message);
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            if (args[0].equals("changeid")) {
                if (sender.hasPermission("resourcepack.changeid")) {
                    if (args.length == 2) {
                        this.state.changeId(sender, args[1]);
                    } else {
                        sender.sendMessage(ChatColor.RED + "You should use /rpack changeid <new resource pack id>");
                    }
                } else {
                    sender.sendMessage(ChatColor.DARK_RED + "You don't have access to this command");
                }
            } else if (args[0].equals("status")) {
                if (sender.hasPermission("resourcepack.status")) {
                    this.state.printStatus(sender);
                } else {
                    sender.sendMessage(ChatColor.DARK_RED + "You don't have access to this command");
                }
            } else if (args[0].equals("sync")) {
                if (sender.hasPermission("resourcepack.sync")) {
                    this.state.sync(sender);
                } else {
                    sender.sendMessage(ChatColor.DARK_RED + "You don't have access to this command");
                }
            } else if (args[0].equals("reload-config")) {
                if (sender.hasPermission("resourcepack.reload-config")) {
                    this.reloadConfig();
                    sender.sendMessage(ChatColor.GREEN + "Config should have been reloaded");
                } else {
                    sender.sendMessage(ChatColor.DARK_RED + "You don't have access to this command");
                }
            } else {
                return false;
            }
            return true;
        } else {
            return false;
        }
    }
}
