package com.moulberry.axiom.operations;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.BiomeBuffer;
import com.moulberry.axiom.integration.Integration;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SetBiomeBufferOperation implements PendingOperation {

    private final ServerPlayer player;
    private final BiomeBuffer biomeBuffer;
    private boolean finished = false;

    private static class BiomeEntry {
        final int x;
        final int y;
        final int z;
        final ResourceKey<Biome> biome;
        BiomeEntry(int x, int y, int z, ResourceKey<Biome> biome) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.biome = biome;
        }
    }

    public SetBiomeBufferOperation(ServerPlayer player, BiomeBuffer biomeBuffer) {
        this.player = player;
        this.biomeBuffer = biomeBuffer;
    }

    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    public ServerPlayer executor() {
        return this.player;
    }

    @Override
    public void tick(ServerLevel level) {
        if (this.finished) return;
        try {
            int minSection = level.getMinSectionY();
            int maxSection = level.getMaxSectionY();

            Optional<Registry<Biome>> registryOptional = level.registryAccess().lookup(Registries.BIOME);
            if (registryOptional.isEmpty()) {
                this.finished = true;
                return;
            }
            Registry<Biome> registry = registryOptional.get();

            Set<LevelChunk> changedChunks = new HashSet<>();

            this.biomeBuffer.forEachEntry((x, y, z, biome) -> {
                int cy = y >> 2;
                if (cy < minSection || cy > maxSection) {
                    return;
                }

                var holder = registry.get(biome);
                if (holder.isPresent()) {
                    LevelChunk chunk = (LevelChunk) level.getChunk(x >> 2, z >> 2, ChunkStatus.FULL, false);
                    if (chunk == null) return;

                    var section = chunk.getSection(level.getSectionIndexFromSectionY(cy));
                    PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) section.getBiomes();

                    if (!Integration.canPlaceBlock(player.getBukkitEntity(),
                        new Location(player.getBukkitEntity().getWorld(), (x<<2)+1, (y<<2)+1, (z<<2)+1))) return;

                    container.set(x & 3, y & 3, z & 3, holder.get());
                    changedChunks.add(chunk);
                }
            });

            var chunkMap = level.getChunkSource().chunkMap;
            HashMap<ServerPlayer, List<LevelChunk>> map = new HashMap<>();
            for (LevelChunk chunk : changedChunks) {
                chunk.markUnsaved();
                ChunkPos chunkPos = chunk.getPos();
                for (ServerPlayer serverPlayer2 : chunkMap.getPlayers(chunkPos, false)) {
                    map.computeIfAbsent(serverPlayer2, serverPlayer -> new ArrayList<>()).add(chunk);
                }
            }
            map.forEach((serverPlayer, list) -> serverPlayer.connection.send(ClientboundChunksBiomesPacket.forChunks(list)));
        } finally {
            this.finished = true;
        }
    }

    @Override
    public void startFolia(ServerLevel level, Runnable onComplete) {
        Map<Long, List<BiomeEntry>> grouped = new HashMap<>();
        this.biomeBuffer.forEachEntry((x, y, z, biome) -> {
            long chunkPos = ChunkPos.pack(x >> 2, z >> 2);
            grouped.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(new BiomeEntry(x, y, z, biome));
        });

        if (grouped.isEmpty()) {
            this.finished = true;
            onComplete.run();
            return;
        }

        AtomicInteger pendingChunks = new AtomicInteger(grouped.size());
        int minSection = level.getMinSectionY();
        int maxSection = level.getMaxSectionY();
        Optional<Registry<Biome>> registryOptional = level.registryAccess().lookup(Registries.BIOME);

        if (registryOptional.isEmpty()) {
            this.finished = true;
            onComplete.run();
            return;
        }
        Registry<Biome> registry = registryOptional.get();

        for (Map.Entry<Long, List<BiomeEntry>> entry : grouped.entrySet()) {
            long chunkPosLong = entry.getKey();
            int cx = ChunkPos.getX(chunkPosLong);
            int cz = ChunkPos.getZ(chunkPosLong);
            List<BiomeEntry> entries = entry.getValue();

            org.bukkit.Bukkit.getRegionScheduler().run(AxiomPaper.PLUGIN, level.getWorld(), cx, cz, task -> {
                try {
                    LevelChunk chunk = (LevelChunk) level.getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk != null) {
                        boolean modified = false;
                        for (BiomeEntry bi : entries) {
                            int cy = bi.y >> 2;
                            if (cy < minSection || cy > maxSection) {
                                continue;
                            }
                            var holder = registry.get(bi.biome);
                            if (holder.isPresent()) {
                                var section = chunk.getSection(level.getSectionIndexFromSectionY(cy));
                                PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) section.getBiomes();

                                if (!Integration.canPlaceBlock(player.getBukkitEntity(),
                                    new Location(player.getBukkitEntity().getWorld(), (bi.x<<2)+1, (bi.y<<2)+1, (bi.z<<2)+1))) continue;

                                container.set(bi.x & 3, bi.y & 3, bi.z & 3, holder.get());
                                modified = true;
                            }
                        }

                        if (modified) {
                            chunk.markUnsaved();
                            ClientboundChunksBiomesPacket packet = ClientboundChunksBiomesPacket.forChunks(List.of(chunk));
                            var chunkMap = level.getChunkSource().chunkMap;
                            for (ServerPlayer serverPlayer2 : chunkMap.getPlayers(chunk.getPos(), false)) {
                                serverPlayer2.getBukkitEntity().getScheduler().run(AxiomPaper.PLUGIN, taskPlayer -> {
                                    if (!serverPlayer2.hasDisconnected()) {
                                        serverPlayer2.connection.send(packet);
                                    }
                                }, null);
                            }
                        }
                    }
                } finally {
                    if (pendingChunks.decrementAndGet() == 0) {
                        this.finished = true;
                        onComplete.run();
                    }
                }
            });
        }
    }
}
