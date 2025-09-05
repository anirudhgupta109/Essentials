
package net.essentialsx.discordlink;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class PlayerLinkStore {
    private final Gson gson = new Gson();
    private final EssentialsDiscordLink plugin;
    private final File linkFile;
    private final BiMap<String, String> uuidToDiscordIdMap;
    private final AtomicBoolean mapDirty = new AtomicBoolean(false);
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    public PlayerLinkStore(final EssentialsDiscordLink plugin) throws IOException {
        this.plugin = plugin;
        this.linkFile = new File(plugin.getDataFolder(), "player-links.json");
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IOException("Unable to create player link file!");
        }
        if (!linkFile.exists() && !linkFile.createNewFile()) {
            throw new IOException("Unable to create player link file!");
        }
        try (final Reader reader = new FileReader(linkFile)) {
            final Map<String, String> map = gson.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
            uuidToDiscordIdMap = map == null ? Maps.synchronizedBiMap(HashBiMap.create()) : Maps.synchronizedBiMap(HashBiMap.create(map));
        }

        executorService.scheduleWithFixedDelay(() -> {
            if (!mapDirty.compareAndSet(true, false)) {
                return;
            }

            if (plugin.getEss().getSettings().isDebug()) {
                plugin.getLogger().log(Level.INFO, "Saving player links to disk...");
            }

            final Map<String, String> clone;
            clone = new HashMap<>(uuidToDiscordIdMap);
            try (final Writer writer = new FileWriter(linkFile)) {
                gson.toJson(clone, writer);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player links!", e);
                mapDirty.set(true);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    public void add(final UUID uuid, final String discordId) {
        uuidToDiscordIdMap.forcePut(uuid.toString(), discordId);
        plugin.getLogger().log(java.util.logging.Level.INFO, "Added player link for " + uuid.toString() + " and " + discordId);
        queueSave();
    }

    public String getDiscordId(final UUID uuid) {
        return uuidToDiscordIdMap.get(uuid.toString());
    }

    public void remove(final UUID uuid) {
        uuidToDiscordIdMap.remove(uuid.toString());
        plugin.getLogger().log(java.util.logging.Level.INFO, "Removed player link for " + uuid.toString());
        queueSave();
    }

    public void queueSave() {
        mapDirty.set(true);
    }

    public void shutdown() {
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().log(Level.SEVERE, "Timed out while saving!");
                executorService.shutdownNow();
            }
            if (mapDirty.get()) {
                try (final Writer writer = new FileWriter(linkFile)) {
                    gson.toJson(uuidToDiscordIdMap, writer);
                }
            }
        } catch (InterruptedException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to shutdown player links save!", e);
            executorService.shutdownNow();
        }
    }
}
