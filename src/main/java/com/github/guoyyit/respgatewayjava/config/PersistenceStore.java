package com.github.guoyyit.respgatewayjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.respgatewayjava.routing.Topology;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class PersistenceStore {

    public static final class PersistedTopology {
        public int shardCount;
        public long epoch;
        public String policy;

        public PersistedTopology() {
        }

        public PersistedTopology(int shardCount, long epoch, String policy) {
            this.shardCount = shardCount;
            this.epoch = epoch;
            this.policy = policy;
        }
    }

    private final Path directory;
    private final Path topologyFile;
    private final Path configFile;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public PersistenceStore(String dataDir) {
        this.directory = Path.of(dataDir);
        this.topologyFile = directory.resolve("topology.json");
        this.configFile = directory.resolve("gateway-config.json");
    }

    public void save(Topology topology, GatewayConfig config) {
        save(topology, config, config.topologyChangePolicy);
    }

    public synchronized void save(Topology topology, GatewayConfig config, String policy) {
        try {
            Files.createDirectories(directory);
            PersistedTopology persistedTopology =
                    new PersistedTopology(topology.shardCount(), topology.epoch(), policy);
            writeAtomically(topologyFile, objectMapper.writeValueAsBytes(persistedTopology));
            writeAtomically(configFile, objectMapper.writeValueAsBytes(config));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public PersistedTopology loadTopology() {
        if (!Files.isRegularFile(topologyFile)) {
            return null;
        }
        try {
            return objectMapper.readValue(topologyFile.toFile(), PersistedTopology.class);
        } catch (IOException exception) {
            return null;
        }
    }

    public GatewayConfig loadConfig() {
        if (!Files.isRegularFile(configFile)) {
            return null;
        }
        try {
            return objectMapper.readValue(configFile.toFile(), GatewayConfig.class);
        } catch (IOException exception) {
            return null;
        }
    }

    private void writeAtomically(Path target, byte[] payload) throws IOException {
        Path temporary = directory.resolve(target.getFileName() + ".tmp");
        Files.write(temporary, payload);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
