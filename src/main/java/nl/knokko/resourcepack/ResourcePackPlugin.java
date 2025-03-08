package nl.knokko.resourcepack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class ResourcePackPlugin extends JavaPlugin implements Listener {

	private AllPacksState state;
	private String urlPrefix;

	@Override
	public void onEnable() {
		this.saveDefaultConfig();
		FileConfiguration config = this.getConfig();
		Bukkit.getPluginManager().registerEvents(this, this);
		this.urlPrefix = config.getString("resource-pack-host-url", "http://49.12.188.159/");
		this.state = new AllPacksState(this.getDataFolder(), this.urlPrefix);

		// Sync every 25 minutes
		int syncPeriod = 20 * 60 * 25;
		Bukkit.getScheduler().scheduleSyncRepeatingTask(
				this, () -> this.state.sync(Bukkit.getConsoleSender(), null), syncPeriod, syncPeriod
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
		//noinspection SynchronizeOnNonFinalField
		synchronized (this.state) {
			String worldName = event.getPlayer().getWorld().getName();
			String resourcePackUrl = this.state.getCurrentResourcePackUrl(worldName);
			byte[] sha1 = this.state.getBinarySha1Hash(worldName);
			if (resourcePackUrl != null && sha1 != null) {
				event.getPlayer().setResourcePack(resourcePackUrl, sha1);
			}
		}
	}

	@EventHandler
	public void handleWorldSpecificPacks(PlayerChangedWorldEvent event) {
		//noinspection SynchronizeOnNonFinalField
		synchronized (this.state) {
			String oldWorldName = event.getFrom().getName();
			String newWorldName = event.getPlayer().getWorld().getName();

			String oldUrl = this.state.getCurrentResourcePackUrl(oldWorldName);
			byte[] oldHash = this.state.getBinarySha1Hash(oldWorldName);
			String newUrl = this.state.getCurrentResourcePackUrl(newWorldName);
			byte[] newHash = this.state.getBinarySha1Hash(newWorldName);
			if (!Objects.equals(oldUrl, newUrl) || !Arrays.equals(oldHash, newHash)) {
				if (newUrl != null && newHash != null) {
					event.getPlayer().setResourcePack(newUrl, newHash);
				}
			}
		}
	}

	@EventHandler
	public void forceResourcePack(PlayerResourcePackStatusEvent event) {
		if (GeyserSupport.isBedrock(event.getPlayer())) return;
		FileConfiguration config = this.getConfig();

		if (event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED) {
			String rejectCommand = config.getString("reject-command");
			if (rejectCommand != null && !rejectCommand.isEmpty()) {
				rejectCommand = rejectCommand.replaceAll("<player>", event.getPlayer().getName());
				Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rejectCommand);
			} else if (config.getBoolean("kick-upon-reject")) {
				event.getPlayer().kickPlayer(config.getString("force-reject-message"));
			} else {
				String message = config.getString("optional-reject-message");
				if (message != null && !message.isEmpty()) {
					event.getPlayer().sendMessage(message);
				}
			}
		}

		if (event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
			String failedCommand = config.getString("failed-command");
			if (failedCommand != null && !failedCommand.isEmpty()) {
				failedCommand = failedCommand.replaceAll("<player>", event.getPlayer().getName());
				Bukkit.dispatchCommand(Bukkit.getConsoleSender(), failedCommand);
			} else if (config.getBoolean("kick-upon-failed-download")) {
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
			//noinspection IfCanBeSwitch
			if (args[0].equals("changeid")) {
				if (sender.hasPermission("resourcepack.changeid")) {
					if (args.length == 2 || args.length == 3) {
						String worldName = args.length == 3 ? args[2] : null;
						this.state.changeId(sender, args[1], worldName);
					} else {
						sender.sendMessage(ChatColor.RED + "You should use /rpack changeid <new resource pack id> [world name]");
					}
				} else {
					sender.sendMessage(ChatColor.DARK_RED + "You don't have access to this command");
				}
			} else if (args[0].equals("remove")) {
				if (sender.hasPermission("resourcepack.changeid")) {
					if (args.length == 1 || args.length == 2) {
						String worldName = args.length == 2 ? args[1] : null;
						if (this.state.remove(sender, worldName)) {
							if (worldName == null) sender.sendMessage(ChatColor.GREEN + "The default resourcepack " +
									"should be gone after you rejoin the server");
							else sender.sendMessage(ChatColor.GREEN + "The resourcepack in world " + worldName +
									" should be gone after you rejoin the server");
						} else sender.sendMessage(
								ChatColor.RED + "No resourcepack was configured for world " + worldName
						);
					} else {
						sender.sendMessage(ChatColor.RED + "You should use /rpack remove [world name]");
					}
				} else {
					sender.sendMessage(ChatColor.DARK_RED + "You don't have access to this command");
				}
			} else if (args[0].equals("list")) {
				if (sender.hasPermission("resourcepack.status")) {
					List<String> worldNames = this.state.getWorldNames();
					if (worldNames.contains(null)) sender.sendMessage("You have configured a default resourcepack");
					else sender.sendMessage("You haven't configured a default resourcepack");

					if (worldNames.stream().noneMatch(Objects::nonNull)) sender.sendMessage(
							"You haven't configured any world-specific resourcepacks"
					); else sender.sendMessage(
							"You have configured world-specific resourcepacks for the following worlds:"
					);
					for (String worldName : worldNames) {
						if (worldName == null) continue;
						sender.sendMessage(" - " + worldName);
					}
				} else {
					sender.sendMessage(ChatColor.DARK_RED + "You don't have access to this command");
				}
			} else if (args[0].equals("status")) {
				if (sender.hasPermission("resourcepack.status")) {
					String worldName = args.length == 2 ? args[1] : null;
					if (!this.state.printStatus(sender, worldName)) {
						sender.sendMessage(ChatColor.RED + "No resourcepack is configured for world " + worldName);
					}
				} else {
					sender.sendMessage(ChatColor.DARK_RED + "You don't have access to this command");
				}
			} else if (args[0].equals("sync")) {
				if (sender.hasPermission("resourcepack.sync")) {
					String worldName = args.length == 2 ? args[1] : null;
					this.state.sync(sender, worldName);
				} else {
					sender.sendMessage(ChatColor.DARK_RED + "You don't have access to this command");
				}
			} else if (args[0].equals("reload-config")) {
				if (sender.hasPermission("resourcepack.reload-config")) {
					this.reloadConfig();
					sender.sendMessage(ChatColor.GREEN + "Config should have been reloaded");
					if (!Objects.equals(this.urlPrefix, getConfig().getString("resource-pack-host-url"))) {
						sender.sendMessage(ChatColor.YELLOW + "It looks like you changed the resource pack host url. " +
								"This change will be applied after you restart the server.");
					}
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
