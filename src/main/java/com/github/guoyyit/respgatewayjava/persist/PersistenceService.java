package com.github.guoyyit.respgatewayjava.persist;

import com.github.guoyyit.respgatewayjava.config.GatewayProperties;
import com.github.guoyyit.respgatewayjava.shard.Entry;
import com.github.guoyyit.respgatewayjava.shard.Shard;
import com.github.guoyyit.respgatewayjava.shard.ShardManager;
import com.github.guoyyit.respgatewayjava.shard.ShardStore;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;

public class PersistenceService {

    static final String META_FILE = "gateway.meta";
    static final String SHARD_FILE_PREFIX = "shard-";
    static final String SHARD_FILE_SUFFIX = ".dat";

    private final Path dataDir;

    public PersistenceService(GatewayProperties properties) {
        this.dataDir = Path.of(properties.getDataDir());
    }

    public PersistenceService(Path dataDir) {
        this.dataDir = dataDir;
    }

    public synchronized void save(ShardManager shardManager) {
        try {
            Files.createDirectories(dataDir);
            Path metaTmp = dataDir.resolve(META_FILE + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(metaTmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("version=1\n");
                writer.write("shardCount=" + shardManager.getShardCount() + '\n');
                writer.write("epoch=" + shardManager.getEpoch() + '\n');
            }
            Files.move(metaTmp, dataDir.resolve(META_FILE),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            for (Shard shard : shardManager.getShards()) {
                Path shardFile = dataDir.resolve(SHARD_FILE_PREFIX + shard.getId() + SHARD_FILE_SUFFIX);
                Path shardTmp = dataDir.resolve(shardFile.getFileName() + ".tmp");
                try (BufferedWriter writer = Files.newBufferedWriter(shardTmp, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (Map.Entry<String, Entry> entry : shard.getStore().snapshot().entrySet()) {
                        writeEntry(writer, entry.getKey(), entry.getValue());
                    }
                }
                Files.move(shardTmp, shardFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist gateway state", e);
        }
    }

    private void writeEntry(BufferedWriter writer, String key, Entry entry) throws IOException {
        writer.write("E\t");
        writer.write(Long.toString(entry.getExpireAtEpochMillis()));
        writer.write('\t');
        writer.write(Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)));
        writer.write('\t');
        writer.write(Base64.getEncoder().encodeToString(entry.getValue()));
        writer.write('\n');
    }

    public int loadShardCount() {
        return readMeta().getOrDefault("shardCount", 0L).intValue();
    }

    public long loadEpoch() {
        return readMeta().getOrDefault("epoch", 1L);
    }

    private Map<String, Long> readMeta() {
        Path meta = dataDir.resolve(META_FILE);
        if (!Files.exists(meta)) {
            return Map.of();
        }
        Map<String, Long> result = new java.util.HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String name = line.substring(0, split);
                String value = line.substring(split + 1);
                if ("shardCount".equals(name) || "epoch".equals(name)) {
                    result.put(name, Long.parseLong(value));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read gateway meta", e);
        }
        return result;
    }

    public void loadData(ShardManager shardManager) {
        for (Shard shard : shardManager.getShards()) {
            Path shardFile = dataDir.resolve(SHARD_FILE_PREFIX + shard.getId() + SHARD_FILE_SUFFIX);
            if (!Files.exists(shardFile)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(shardFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    loadLine(line, shard);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("failed to load shard " + shard.getId(), e);
            }
        }
    }

    private void loadLine(String line, Shard shard) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 4 || !"E".equals(fields[0])) {
            return;
        }
        long expireAtEpochMillis = Long.parseLong(fields[1]);
        byte[] keyBytes = Base64.getDecoder().decode(fields[2]);
        byte[] value = Base64.getDecoder().decode(fields[3]);
        if (expireAtEpochMillis != Entry.NO_WALL_EXPIRY
                && expireAtEpochMillis <= System.currentTimeMillis()) {
            return;
        }
        shard.getStore().restore(new String(keyBytes, StandardCharsets.UTF_8),
                ShardStore.restoreEntry(value, expireAtEpochMillis));
    }
}
