package nl.knokko.resourcepack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ResourcePackState {

    private final File folder;

    private String currentResourcePackId;
    private byte[] binarySha1Hash;
    private long lastSyncTime = 0L;

    private final Queue<Runnable> bukkitThreadQueue = new ConcurrentLinkedQueue<>();
    private final BlockingQueue<Runnable> backgroundThreadQueue = new LinkedBlockingQueue<>();

    private boolean isStopped;

    public ResourcePackState(File folder) {
        this.folder = folder;

        if (!folder.isDirectory() && !folder.mkdirs()) {
            Bukkit.getLogger().severe("Can't create folder plugins/ResourcePack");
        }

        List<File> candidateResourcePackFiles = new ArrayList<>(1);
        File[] existingFiles = folder.listFiles();
        if (existingFiles != null) {
            for (File candidateResourcePackFile : existingFiles) {
                if (candidateResourcePackFile.isFile() && candidateResourcePackFile.getName().endsWith(".zip")) {
                    candidateResourcePackFiles.add(candidateResourcePackFile);
                }
            }
        }

        if (!candidateResourcePackFiles.isEmpty()) {
            File resourcePackFile = candidateResourcePackFiles.get(0);

            if (candidateResourcePackFiles.size() > 1) {
                Bukkit.getLogger().warning("Multiple resource pack files are present in plugins/ResourcePack. Only the latest will be kept.");

                for (File candidateFile : candidateResourcePackFiles) {
                    if (candidateFile.lastModified() > resourcePackFile.lastModified()) {
                        resourcePackFile = candidateFile;
                    }
                }

                for (File file : candidateResourcePackFiles) {
                    if (file != resourcePackFile) {
                        if (!file.delete()) {
                            Bukkit.getLogger().warning("Failed to delete outdated resource pack file " + file);
                        }
                    }
                }
            }

            this.currentResourcePackId = resourcePackFile.getName().substring(0, resourcePackFile.getName().length() - 4);
            this.updateSha1Hash();
            this.sync(Bukkit.getConsoleSender());
        }

        new Thread(() -> {
            while (!this.isStopped) {
                try {
                    backgroundThreadQueue.take().run();
                } catch (InterruptedException e) {
                    // This shouldn't happen
                    e.printStackTrace();
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

    private File getResourcePackFile() {
        return new File(this.folder + "/" + this.currentResourcePackId + ".zip");
    }

    private synchronized void updateSha1Hash() {
        try {
            File resourcePackFile = this.getResourcePackFile();
            this.propagate(
                    Files.newInputStream(resourcePackFile.toPath()), new VoidOutputStream(),
                    true, true, null, -1
            );
        } catch (IOException ioTrouble) {
            Bukkit.getLogger().severe(
                    "Failed to read resource pack " + this.currentResourcePackId + ": " + ioTrouble.getMessage()
            );
            this.currentResourcePackId = null;
        } catch (NoSuchAlgorithmException noSha1Support) {
            Bukkit.getLogger().severe(
                    "It looks like your server does not support SHA-1, so this plug-in won't work on your server."
            );
            this.currentResourcePackId = null;
        }
    }

    private void propagate(
            InputStream source, OutputStream destination,
            boolean updateSha1, boolean closeDestination,
            CommandSender progressListener, long totalLength
    ) throws IOException, NoSuchAlgorithmException {
        DigestInputStream digestInput = null;
        if (updateSha1) {
            digestInput = new DigestInputStream(source, MessageDigest.getInstance("SHA-1"));
            source = digestInput;
        }

        byte[] buffer = new byte[100_000];
        long totalNumReadBytes = 0;
        while (true) {
            int numReadBytes = source.read(buffer);
            if (numReadBytes == -1) break;

            destination.write(buffer, 0, numReadBytes);

            long oldMillion = totalNumReadBytes / 1_000_000;
            totalNumReadBytes += numReadBytes;
            long newMillion = totalNumReadBytes / 1_000_000;
            if (progressListener != null && oldMillion != newMillion) {
                sendOnBukkitThread(
                        progressListener,
                        ChatColor.AQUA + "Progress: " + String.format("%.1f", 100.0 * totalNumReadBytes / totalLength) + "%"
                );
            }
        }
        if (updateSha1) {
            this.binarySha1Hash = digestInput.getMessageDigest().digest();
        }
        source.close();
        destination.flush();
        if (closeDestination) {
            destination.close();
        }
    }

    private void sendOnBukkitThread(CommandSender sender, String message) {
        bukkitThreadQueue.add(() -> sender.sendMessage(message));
    }

    private void notifyPlayersAboutNewResourcePack() {
        Bukkit.broadcastMessage("A new server resource pack has been configured. You will get it once you reconnect to this server.");
    }

    public synchronized void sync(CommandSender sender) {
        if (this.currentResourcePackId == null) {
            sender.sendMessage(ChatColor.RED + "You need to use /rpack changeid <resource pack id> before running this command.");
            return;
        }

        // TODO Allow sender to be null

        File resourcePackFile = this.getResourcePackFile();
        boolean hasResourcePackLocally = resourcePackFile.exists();

        backgroundThreadQueue.add(() -> {
            try {
                URL url = new URL(this.getCurrentResourcePackUrl());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                if (!hasResourcePackLocally) {
                    connection.setRequestMethod("GET");
                } else {
                    connection.setRequestMethod("HEAD");
                }
                connection.connect();

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    if (!hasResourcePackLocally) {
                        sendOnBukkitThread(sender, ChatColor.BLUE + "Downloading resource pack from the resource pack server...");
                        try {
                            OutputStream fileOutput = Files.newOutputStream(resourcePackFile.toPath());
                            this.propagate(
                                    connection.getInputStream(), fileOutput,
                                    true, true, sender, connection.getContentLength()
                            );

                            bukkitThreadQueue.add(() -> {
                                sender.sendMessage(ChatColor.BLUE + "Finished download resource pack from the resource pack server");
                                this.notifyPlayersAboutNewResourcePack();
                            });

                            this.lastSyncTime = System.currentTimeMillis();
                        } catch (IOException cantDownload) {
                            sendOnBukkitThread(sender, ChatColor.RED + "Failed to download resource pack from the resource pack server: " + cantDownload.getMessage());
                        }
                    } else {
                        sendOnBukkitThread(sender, ChatColor.GREEN + "Sync succeeded");
                        this.lastSyncTime = System.currentTimeMillis();
                    }
                } else if (responseCode == 404) {
                    if (hasResourcePackLocally) {
                        try {
                            this.postResourcePack(sender);
                        } catch (IOException cantUpload) {
                            sendOnBukkitThread(sender, "Failed to upload the resource pack to the resource pack server: " + cantUpload.getMessage());
                        }
                    } else {
                        sendOnBukkitThread(sender, "The resource pack server no longer has this resource pack. You need to re-upload it.");
                        this.currentResourcePackId = null;
                    }
                } else {
                    sendOnBukkitThread(sender, "Got unexpected response code " + responseCode + " from the resource pack server.");
                }

                connection.disconnect();
            } catch (MalformedURLException badURL) {
                sendOnBukkitThread(sender, ChatColor.RED + badURL.getMessage());
            } catch (IOException cantReachServer) {
                sendOnBukkitThread(sender, ChatColor.RED + "Can't connect to resource pack server: " + cantReachServer.getMessage());
            } catch (NoSuchAlgorithmException noSha1Support) {
                sendOnBukkitThread(sender, ChatColor.DARK_RED + "Your server doesn't support SHA-1, so this plug-in won't work.");
            }
        });
    }

    public synchronized void changeId(CommandSender sender, String newResourcePackId) {
        if (this.currentResourcePackId != null) {
            File currentFile = this.getResourcePackFile();
            if (currentFile.exists()) {
                if (!currentFile.delete()) {
                    sender.sendMessage(ChatColor.RED + "Failed to delete the old resource pack. This might cause problems later.");
                }
            }
            this.binarySha1Hash = null;
        }

        this.currentResourcePackId = newResourcePackId;
        this.sync(sender);
    }

    public synchronized void printStatus(CommandSender sender) {
        if (this.currentResourcePackId != null) {
            sender.sendMessage("The current resource pack id is " + this.currentResourcePackId);
            File resourcePackFile = this.getResourcePackFile();
            if (resourcePackFile.exists()) {
                sender.sendMessage("A back-up of the resource pack is stored on this server.");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "There is no back-up of the resource pack on this server!");
            }

            if (this.lastSyncTime != 0) {
                Calendar lastSyncCalendar = Calendar.getInstance();
                lastSyncCalendar.setTimeInMillis(this.lastSyncTime);

                int hour = lastSyncCalendar.get(Calendar.HOUR_OF_DAY);
                int minute = lastSyncCalendar.get(Calendar.MINUTE);
                sender.sendMessage("The last synchronization with the resource pack server was at " + hour + ":" + minute + " in timezone " + lastSyncCalendar.getTimeZone().getDisplayName());
            } else {
                sender.sendMessage(ChatColor.YELLOW + "This server hasn't synchronized with the resource pack server yet.");
            }
        } else {
            sender.sendMessage("This plug-in doesn't have a resource pack yet.");
            sender.sendMessage("Use /rpack changeid <resource pack id>");
        }
    }

    private String getResourcePackUrlPrefix() {
        // TODO Allow users to configure the URL?
        return "http://49.12.188.159/";
    }

    private void postResourcePack(CommandSender sender) throws IOException, NoSuchAlgorithmException {
        URL url = new URL(this.getResourcePackUrlPrefix() + "upload-resource-pack/" + this.currentResourcePackId);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.connect();

        sender.sendMessage(ChatColor.BLUE + "Uploading resource pack to the resource pack server...");

        OutputStream uploadOutput = connection.getOutputStream();
        long fileId = System.nanoTime() + System.currentTimeMillis();
        PrintWriter uploadTextOutput = new PrintWriter(uploadOutput);
        uploadTextOutput.print("-----------------------------" + fileId + "\r\n");
        uploadTextOutput.print("Content-Disposition: form-data; name=\"resource-pack\"; filename=\"" + this.currentResourcePackId + ".zip\"\r\n");
        uploadTextOutput.print("Content-Type: application/x-zip-compressed\r\n\r\n");
        uploadTextOutput.flush();

        this.propagate(
                Files.newInputStream(this.getResourcePackFile().toPath()), uploadOutput,
                false, false, sender, this.getResourcePackFile().length()
        );

        uploadTextOutput = new PrintWriter(uploadOutput);
        uploadTextOutput.print("\r\n-----------------------------" + fileId + "--\r\n");
        uploadTextOutput.flush();

        sender.sendMessage(ChatColor.BLUE + "Finished uploading resource pack to the resource pack server");

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            sender.sendMessage(ChatColor.RED + "Failed to upload resource pack: code is " + responseCode);
        } else {
            this.lastSyncTime = System.currentTimeMillis();
            this.notifyPlayersAboutNewResourcePack();
        }
        connection.disconnect();
    }

    public String getCurrentResourcePackUrl() {
        if (this.currentResourcePackId != null) return this.getResourcePackUrlPrefix() + "get-resource-pack/" + this.currentResourcePackId;
        else return null;
    }

    public byte[] getBinarySha1Hash() {
        return binarySha1Hash;
    }
}
