package com.moulberry.axiom.operations;

import com.moulberry.axiom.AxiomConstants;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.buffer.CompressedBlockEntity;
import com.moulberry.axiom.packet.impl.RequestChunkDataPacketListener;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftChunk;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class RequestChunksOperation implements PendingOperation {

    private static final int MAX_CHUNK_FUTURES = 256;
    private boolean finished = false;

    private final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

    private final ServerPlayer serverPlayer;
    private final long id;

    private final LongArrayList getChunkFutures;
    private final LongSet loadedOnlyChunks;
    private  List<CompletableFuture<Chunk>> chunkFutures = new ArrayList<>();
    private final Long2ObjectMap<LongList> sendBlockEntityForPendingChunks;
    private final Long2ObjectMap<IntList> sendSectionsForPendingChunks;
    private final boolean sendBlockEntitiesInChunks;

    private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sendingSections;
    private final Long2ObjectOpenHashMap<CompressedBlockEntity> sendingBlockEntities;
    private final ByteArrayOutputStream baos;

    public RequestChunksOperation(ServerLevel level, ServerPlayer serverPlayer, long id, LongSet chunkFutures, LongSet loadedOnlyChunks, Long2ObjectMap<LongList> sendBlockEntityForPendingChunks, Long2ObjectMap<IntList> sendSectionsForPendingChunks, boolean sendBlockEntitiesInChunks, Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sendingSections, Long2ObjectOpenHashMap<CompressedBlockEntity> sendingBlockEntities, ByteArrayOutputStream baos) {
        this.serverPlayer = serverPlayer;
        this.id = id;
        this.loadedOnlyChunks = loadedOnlyChunks;
        this.sendBlockEntityForPendingChunks = sendBlockEntityForPendingChunks;
        this.sendSectionsForPendingChunks = sendSectionsForPendingChunks;
        this.sendBlockEntitiesInChunks = sendBlockEntitiesInChunks;
        this.sendingSections = sendingSections;
        this.sendingBlockEntities = sendingBlockEntities;
        this.baos = baos;

        LongArrayList getChunkFutures = new LongArrayList(chunkFutures);
        getChunkFutures.unstableSort(LongComparators.NATURAL_COMPARATOR);
        this.getChunkFutures = getChunkFutures;

        if (!VersionHelper.isFolia()) {
            for (long pos : loadedOnlyChunks) {
                int cx = ChunkPos.getX(pos);
                int cz = ChunkPos.getZ(pos);
                LevelChunk chunk = level.getChunkIfLoaded(cx, cz);
                if (chunk != null) {
                    processChunk(chunk);
                }
            }
        }
    }

    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    public ServerPlayer executor() {
        return this.serverPlayer;
    }

    @Override
    public void startFolia(ServerLevel level, Runnable onComplete) {
        if (this.finished) {
            onComplete.run();
            return;
        }

        if (this.serverPlayer.hasDisconnected()) {
            this.finished = true;
            onComplete.run();
            return;
        }

        if (this.getChunkFutures.isEmpty() && this.loadedOnlyChunks.isEmpty()) {
            RequestChunkDataPacketListener.sendResponse(this.serverPlayer, this.id, this.sendingBlockEntities, this.sendingSections);
            this.finished = true;
            onComplete.run();
            return;
        }

        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(this.getChunkFutures.size() + this.loadedOnlyChunks.size());

        // Process load-allowed chunks
        LongIterator iterator = this.getChunkFutures.longIterator();
        while (iterator.hasNext()) {
            long chunkPos = iterator.nextLong();
            int x = ChunkPos.getX(chunkPos);
            int z = ChunkPos.getZ(chunkPos);

            level.getWorld().getChunkAtAsync(x, z).thenAccept(bukkitChunk -> {
                if (this.serverPlayer.hasDisconnected()) {
                    this.finished = true;
                    if (remaining.decrementAndGet() == 0) {
                        onComplete.run();
                    }
                    return;
                }

                int cx = bukkitChunk.getX();
                int cz = bukkitChunk.getZ();
                org.bukkit.Bukkit.getRegionScheduler().run(com.moulberry.axiom.AxiomPaper.PLUGIN, level.getWorld(), cx, cz, task -> {
                    try {
                        if (this.serverPlayer.hasDisconnected()) {
                            this.finished = true;
                            return;
                        }

                        LevelChunk chunk = (LevelChunk) ((org.bukkit.craftbukkit.CraftChunk) bukkitChunk).getHandle(ChunkStatus.FULL);
                        processChunk(chunk);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            if (!this.serverPlayer.hasDisconnected()) {
                                RequestChunkDataPacketListener.sendResponse(this.serverPlayer, this.id, this.sendingBlockEntities, this.sendingSections);
                            }
                            this.finished = true;
                            onComplete.run();
                        }
                    }
                });
            }).exceptionally(t -> {
                if (remaining.decrementAndGet() == 0) {
                    this.finished = true;
                    onComplete.run();
                }
                return null;
            });
        }

        // Process loaded-only chunks
        LongIterator loadedOnlyIterator = this.loadedOnlyChunks.iterator();
        while (loadedOnlyIterator.hasNext()) {
            long chunkPos = loadedOnlyIterator.nextLong();
            int x = ChunkPos.getX(chunkPos);
            int z = ChunkPos.getZ(chunkPos);

            org.bukkit.Bukkit.getRegionScheduler().run(com.moulberry.axiom.AxiomPaper.PLUGIN, level.getWorld(), x, z, task -> {
                try {
                    if (this.serverPlayer.hasDisconnected()) {
                        this.finished = true;
                        return;
                    }

                    LevelChunk chunk = level.getChunkIfLoaded(x, z);
                    if (chunk != null) {
                        processChunk(chunk);
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        if (!this.serverPlayer.hasDisconnected()) {
                            RequestChunkDataPacketListener.sendResponse(this.serverPlayer, this.id, this.sendingBlockEntities, this.sendingSections);
                        }
                        this.finished = true;
                        onComplete.run();
                    }
                }
            });
        }
    }

    @Override
    public void tick(ServerLevel level) {
        if (this.finished) {
            return;
        }

        if (this.serverPlayer.hasDisconnected()) {
            this.finished = true;
            return;
        }

        if (!this.getChunkFutures.isEmpty()) {
            int count = this.chunkFutures.size();
            LongIterator newFutureIterator = this.getChunkFutures.longIterator();
            while (count++ < MAX_CHUNK_FUTURES && newFutureIterator.hasNext()) {
                long chunkPos = newFutureIterator.nextLong();
                newFutureIterator.remove();

                int x = ChunkPos.getX(chunkPos);
                int z = ChunkPos.getZ(chunkPos);
                this.chunkFutures.add(level.getWorld().getChunkAtAsync(x, z));
            }
        }

        Iterator<CompletableFuture<org.bukkit.Chunk>> chunkFutureIterator = this.chunkFutures.iterator();
        while (chunkFutureIterator.hasNext()) {
            CompletableFuture<org.bukkit.Chunk> future = chunkFutureIterator.next();
            if (!future.isDone()) {
                return;
            }

            chunkFutureIterator.remove();

            LevelChunk chunk = (LevelChunk) ((CraftChunk)future.join()).getHandle(ChunkStatus.FULL);
            processChunk(chunk);
        }

        if (!this.getChunkFutures.isEmpty()) {
            return;
        }

        RequestChunkDataPacketListener.sendResponse(this.serverPlayer, this.id, this.sendingBlockEntities, this.sendingSections);
        this.finished = true;
    }

    private void processChunk(LevelChunk chunk) {
        long chunkPosLong = ChunkPos.pack(chunk.locX, chunk.locZ);
        int cx = chunk.locX;
        int cz = chunk.locZ;

        LongList blockEntitiesInChunk = this.sendBlockEntityForPendingChunks.get(chunkPosLong);
        if (blockEntitiesInChunk != null) {
            LongIterator blockEntityIterator = blockEntitiesInChunk.longIterator();
            while (blockEntityIterator.hasNext()) {
                long blockEntityPos = blockEntityIterator.nextLong();
                BlockPos blockEntityPosMutable = new BlockPos(BlockPos.getX(blockEntityPos), BlockPos.getY(blockEntityPos), BlockPos.getZ(blockEntityPos));

                BlockEntity blockEntity = chunk.getBlockEntity(blockEntityPosMutable, LevelChunk.EntityCreationType.CHECK);
                if (blockEntity != null) {
                    CompoundTag tag = blockEntity.saveWithoutMetadata(this.serverPlayer.registryAccess());
                    synchronized (this) {
                        this.sendingBlockEntities.put(blockEntityPos, com.moulberry.axiom.buffer.CompressedBlockEntity.compress(tag, this.baos));
                    }
                }
            }
        }

        IntList sendSectionsInChunk = this.sendSectionsForPendingChunks.get(chunkPosLong);
        if (sendSectionsInChunk != null) {
            boolean hasNonAirSectionInChunk = false;

            IntIterator sectionIterator = sendSectionsInChunk.intIterator();
            while (sectionIterator.hasNext()) {
                int sy = sectionIterator.nextInt();

                int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
                if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) continue;
                LevelChunkSection section = chunk.getSection(sectionIndex);

                if (section.hasOnlyAir()) {
                    synchronized (this) {
                        this.sendingSections.put(BlockPos.asLong(cx, sy, cz), null);
                    }
                } else {
                    PalettedContainer<BlockState> container = section.getStates().copy();
                    synchronized (this) {
                        this.sendingSections.put(BlockPos.asLong(cx, sy, cz), container);
                    }
                    hasNonAirSectionInChunk = true;
                }
            }

            if (this.sendBlockEntitiesInChunks && hasNonAirSectionInChunk) {
                Set<Map.Entry<BlockPos, BlockEntity>> entrySet = chunk.blockEntities.entrySet();
                Iterator<Map.Entry<BlockPos, BlockEntity>> entryIterator;
                if (entrySet instanceof Object2ObjectMap.FastEntrySet fastEntrySet) {
                    entryIterator = fastEntrySet.fastIterator();
                } else {
                    entryIterator = entrySet.iterator();
                }

                while (entryIterator.hasNext()) {
                    Map.Entry<BlockPos, BlockEntity> entry = entryIterator.next();

                    BlockPos blockPos = entry.getKey();
                    int sectionY = blockPos.getY() >> 4;
                    if (!sendSectionsInChunk.contains(sectionY)) {
                        continue;
                    }

                    CompoundTag tag = entry.getValue().saveWithoutMetadata(this.serverPlayer.registryAccess());
                    synchronized (this) {
                        this.sendingBlockEntities.put(blockPos.asLong(), com.moulberry.axiom.buffer.CompressedBlockEntity.compress(tag, this.baos));
                    }
                }
            }
        }
    }
}
