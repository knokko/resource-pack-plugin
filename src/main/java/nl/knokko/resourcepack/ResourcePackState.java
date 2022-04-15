package nl.knokko.resourcepack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class ResourcePackState {

    private final File folder;

    private String currentResourcePackId;
    private byte[] binarySha1Hash;
    private long lastSyncTime = 0L;

    public ResourcePackState(File folder) {
        this.folder = folder;

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
    }

    private File getResourcePackFile() {
        return new File(this.folder + "/" + this.currentResourcePackId + ".zip");
    }

    private void updateSha1Hash() {
        try {
            // TODO Do this on another thread?
            File resourcePackFile = this.getResourcePackFile();
            this.propagate(Files.newInputStream(resourcePackFile.toPath()), new VoidOutputStream(), true);
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

    private void propagate(InputStream source, OutputStream destination, boolean updateSha1) throws IOException, NoSuchAlgorithmException {
        DigestInputStream digestInput = null;
        if (updateSha1) {
            digestInput = new DigestInputStream(source, MessageDigest.getInstance("SHA-1"));
            source = digestInput;
        }
        byte[] buffer = new byte[100_000];
        while (true) {
            int numReadBytes = source.read(buffer);
            if (numReadBytes == -1) break;

            destination.write(buffer, 0, numReadBytes);
        }
        if (updateSha1) {
            this.binarySha1Hash = digestInput.getMessageDigest().digest();
        }
        source.close();
        destination.flush();
        destination.close();
    }

    private void notifyPlayersAboutNewResourcePack() {
        Bukkit.broadcastMessage("A new server resource pack has been configured. You will get it once you reconnect to this server.");
    }

    public void sync(CommandSender sender) {
        if (this.currentResourcePackId == null) {
            sender.sendMessage(ChatColor.RED + "You need to use /rpack changeid <resource pack id> before running this command.");
            return;
        }

        // TODO Allow sender to be null

        File resourcePackFile = this.getResourcePackFile();
        boolean hasResourcePackLocally = resourcePackFile.exists();

        // TODO Do this on another thread?
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
                    sender.sendMessage(ChatColor.BLUE + "Downloading resource pack from the resource pack server...");
                    this.propagate(connection.getInputStream(), Files.newOutputStream(resourcePackFile.toPath()), true);
                    sender.sendMessage(ChatColor.BLUE + "Finished download resource pack from the resource pack server");
                    this.notifyPlayersAboutNewResourcePack();
                } else {
                    sender.sendMessage(ChatColor.GREEN + "Sync succeeded");
                }
                this.lastSyncTime = System.currentTimeMillis();
            } else if (responseCode == 404) {
                if (hasResourcePackLocally) {
                    this.postResourcePack(sender);
                    this.lastSyncTime = System.currentTimeMillis();
                    this.notifyPlayersAboutNewResourcePack();
                } else {
                    sender.sendMessage(ChatColor.RED + "The resource pack server no longer has this resource pack. You need to re-upload it.");
                    this.currentResourcePackId = null;
                }
            } else {
                sender.sendMessage(ChatColor.RED + "Got unexpected response code " + responseCode + " from the resource pack server.");
            }

            connection.disconnect();
        } catch (MalformedURLException badURL) {
            sender.sendMessage(ChatColor.RED + badURL.getMessage());
        } catch (IOException cantReachServer) {
            sender.sendMessage(ChatColor.RED + "Can't connect to resource pack server: " + cantReachServer.getMessage());
        } catch (NoSuchAlgorithmException noSha1Support) {
            sender.sendMessage(ChatColor.DARK_RED + "Your server doesn't support SHA-1, so this plug-in won't work.");
        }
    }

    public void changeId(CommandSender sender, String newResourcePackId) {
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

    public void printStatus(CommandSender sender) {
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
                sender.sendMessage("The last synchronization with the resource pack server was at " + hour + ":" + minute + " in timezone " + lastSyncCalendar.getTimeZone());
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
        return "http://localhost/";
    }

    private void postResourcePack(CommandSender sender) throws IOException, NoSuchAlgorithmException {
        URL url = new URL(this.getResourcePackUrlPrefix() + "upload-resource-pack/" + this.currentResourcePackId);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.connect();

        sender.sendMessage(ChatColor.BLUE + "Uploading resource pack to the resource pack server...");
        this.propagate(Files.newInputStream(this.getResourcePackFile().toPath()), connection.getOutputStream(), false);
        sender.sendMessage(ChatColor.BLUE + "Finished uploading resource pack to the resource pack server");

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            sender.sendMessage(ChatColor.RED + "Failed to upload resource pack: code is " + responseCode);
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
