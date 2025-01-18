package nl.knokko.resourcepack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.io.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AllPacksState {

	private final File dataFolder;
	private final String urlPrefix;

	private final Queue<Runnable> bukkitThreadQueue = new ConcurrentLinkedQueue<>();
	private final BlockingQueue<Runnable> backgroundThreadQueue = new LinkedBlockingQueue<>();

	private final SinglePackState defaultState;
	private final List<SinglePackState> worldStates = new ArrayList<>();

	private boolean isStopped;

	public AllPacksState(File dataFolder, String urlPrefix) {
		this.dataFolder = dataFolder;
		this.urlPrefix = urlPrefix;

		if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
			Bukkit.getLogger().severe("Failed to create data folder: " + dataFolder);
		}
		this.defaultState = new SinglePackState(
				dataFolder, null, urlPrefix, bukkitThreadQueue, backgroundThreadQueue
		);

		File worldsFolder = new File(dataFolder + "/worlds");
		if (!worldsFolder.isDirectory() && !worldsFolder.mkdirs()) {
			Bukkit.getLogger().severe("Failed to create worlds pack folder: " + worldsFolder);
		}
		for (File worldFolder : worldsFolder.listFiles()) {
			this.worldStates.add(new SinglePackState(
					worldFolder, worldFolder.getName(), urlPrefix, bukkitThreadQueue, backgroundThreadQueue
			));
		}

		new Thread(() -> {
			while (!this.isStopped) {
				try {
					backgroundThreadQueue.take().run();
				} catch (InterruptedException shouldNotHappen) {
					throw new RuntimeException(shouldNotHappen);
				}
			}
		}).start();
	}

	public void stop() {
		this.backgroundThreadQueue.add(() -> this.isStopped = true);
	}

	public void updateBukkitThreadTasks() {
		while (true) {
			Runnable nextTask = bukkitThreadQueue.poll();
			if (nextTask == null) break;
			nextTask.run();
		}
	}

	public synchronized void sync(CommandSender sender, String worldName) {
		if (worldName == null) defaultState.sync(sender);
		for (SinglePackState state : worldStates) {
			if (worldName == null || worldName.equals(state.worldName)) state.sync(sender);
		}
	}

	private SinglePackState getState(String worldName, boolean strict) {
		if (worldName == null) return defaultState;
		for (SinglePackState state : worldStates) {
			if (state.worldName.equals(worldName)) return state;
		}
		return strict ? null : defaultState;
	}

	public synchronized String getCurrentResourcePackUrl(String worldName) {
		return Objects.requireNonNull(getState(worldName, false)).getCurrentResourcePackUrl();
	}

	public synchronized byte[] getBinarySha1Hash(String worldName) {
		return Objects.requireNonNull(getState(worldName, false)).getBinarySha1Hash();
	}

	public synchronized void changeId(CommandSender sender, String newResourcePackId, String worldName) {
		SinglePackState state = getState(worldName, true);
		if (state == null) {
			state = new SinglePackState(
					new File(dataFolder + "/worlds/" + worldName),
					worldName, urlPrefix, bukkitThreadQueue, backgroundThreadQueue
			);
			worldStates.add(state);
		}
		state.changeId(sender, newResourcePackId);
	}

	public synchronized boolean printStatus(CommandSender sender, String worldName) {
		SinglePackState state = getState(worldName, true);
		if (state == null) return false;
		state.printStatus(sender);
		return true;
	}

	public synchronized boolean remove(CommandSender sender, String worldName) {
		SinglePackState state = getState(worldName, true);
		if (state == null) return false;

		if (state == defaultState) {
			defaultState.clear(sender);
		} else {
			File folder = state.folder;
			File[] files = folder.listFiles();
			if (files != null) {
				for (File file : files) {
					if (!file.delete()) sender.sendMessage(ChatColor.YELLOW + "Failed to delete " + file);
				}
			}
			if (!folder.delete()) sender.sendMessage(ChatColor.YELLOW + "Failed to delete " + folder);
			worldStates.remove(state);
		}

		return true;
	}

	public synchronized List<String> getWorldNames() {
		List<String> result = new ArrayList<>(1 + worldStates.size());
		if (defaultState.getBinarySha1Hash() != null) result.add(null);
		for (SinglePackState state : worldStates) result.add(state.worldName);
		return result;
	}
}
