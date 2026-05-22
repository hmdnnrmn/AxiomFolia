package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomConstants;
import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.buffer.CompressedBlockEntity;
import com.moulberry.axiom.operations.RequestChunksOperation;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;

public class RequestChunkDataPacketListener implements PacketHandler<RequestChunkDataPacketListener.ParsedPayload> {

    private static final Identifier RESPONSE_ID = VersionHelper.createIdentifier("axiom:response_chunk_data");

    private final AxiomPaper plugin;
    private final Map<net.minecraft.network.RegistryFriendlyByteBuf, SessionInfo> sessionInfoMap = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public record SessionInfo(boolean shouldSendBlockEntities, int maxChunkLoadDistance) {}

    public record ParsedPayload(
        long id,
        ResourceKey<Level> worldKey,
        boolean sendBlockEntitiesInChunks,
        LongList blockEntities,
        LongList chunkSections,
        boolean shouldSendBlockEntities,
        int maxChunkLoadDistance
    ) {}

    public RequestChunkDataPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handleAsync() {
        return true;
    }

    @Override
    public boolean precheck(Player bukkitPlayer, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (friendlyByteBuf.readableBytes() < 8) {
            return false;
        }
        int readerIndex = friendlyByteBuf.readerIndex();
        long id = friendlyByteBuf.readLong();
        friendlyByteBuf.readerIndex(readerIndex); // Reset reader index

        if (!plugin.canUseAxiom(bukkitPlayer, AxiomPermission.CHUNK_REQUEST) || plugin.isMismatchedDataVersion(bukkitPlayer.getUniqueId())) {
            ServerPlayer player = ((CraftPlayer)bukkitPlayer).getHandle();
            sendEmptyResponse(player, id);
            return false;
        }

        if (!plugin.canModifyWorld(bukkitPlayer, bukkitPlayer.getWorld())) {
            ServerPlayer player = ((CraftPlayer)bukkitPlayer).getHandle();
            sendEmptyResponse(player, id);
            return false;
        }

        boolean shouldSendBlockEntities = plugin.hasPermission(bukkitPlayer, AxiomPermission.CHUNK_REQUESTBLOCKENTITY);
        int maxChunkLoadDistance = plugin.getMaxChunkLoadDistance(bukkitPlayer.getWorld());

        sessionInfoMap.put(friendlyByteBuf, new SessionInfo(shouldSendBlockEntities, maxChunkLoadDistance));
        return true;
    }

    @Override
    public ParsedPayload parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        SessionInfo info = sessionInfoMap.remove(friendlyByteBuf);
        boolean shouldSendBlockEntities = info != null ? info.shouldSendBlockEntities : false;
        int maxChunkLoadDistance = info != null ? info.maxChunkLoadDistance : 0;

        long id = friendlyByteBuf.readLong();
        ResourceKey<Level> worldKey = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        boolean sendBlockEntitiesInChunks = friendlyByteBuf.readBoolean() && shouldSendBlockEntities;

        LongList blockEntities = new LongArrayList();
        int blockEntityCount = friendlyByteBuf.readVarInt();
        if (!shouldSendBlockEntities) {
            friendlyByteBuf.skipBytes(Long.BYTES * blockEntityCount);
        } else {
            for (int i = 0; i < blockEntityCount; i++) {
                blockEntities.add(friendlyByteBuf.readLong());
            }
        }

        LongList chunkSections = new LongArrayList();
        int chunkCount = friendlyByteBuf.readVarInt();
        for (int i = 0; i < chunkCount; i++) {
            chunkSections.add(friendlyByteBuf.readLong());
        }

        return new ParsedPayload(id, worldKey, sendBlockEntitiesInChunks, blockEntities, chunkSections, shouldSendBlockEntities, maxChunkLoadDistance);
    }

    @Override
    public void apply(Player bukkitPlayer, ParsedPayload parsed) {
        ServerPlayer player = ((CraftPlayer)bukkitPlayer).getHandle();

        MinecraftServer server = player.level().getServer();
        if (server == null) {
            sendEmptyResponse(player, parsed.id);
            return;
        }

        ServerLevel level = server.getLevel(parsed.worldKey);
        if (level == null || level != player.level()) {
            sendEmptyResponse(player, parsed.id);
            return;
        }


        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        int playerSectionX = player.getBlockX() >> 4;
        int playerSectionZ = player.getBlockZ() >> 4;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sendingSections = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<CompressedBlockEntity> sendingBlockEntities = new Long2ObjectOpenHashMap<>();

        LongSet chunkFutures = new LongOpenHashSet();
        LongSet loadedOnlyChunks = new LongOpenHashSet();
        Long2ObjectMap<LongList> sendBlockEntityForPendingChunks = new Long2ObjectOpenHashMap<>();
        Long2ObjectMap<IntList> sendSectionsForPendingChunks = new Long2ObjectOpenHashMap<>();

        for (long pos : parsed.blockEntities) {
            mutableBlockPos.set(pos);

            if (level.isOutsideBuildHeight(mutableBlockPos)) {
                continue;
            }

            int chunkX = mutableBlockPos.getX() >> 4;
            int chunkZ = mutableBlockPos.getZ() >> 4;

            int distance = Math.abs(playerSectionX - chunkX) + Math.abs(playerSectionZ - chunkZ);
            boolean canLoad = distance < parsed.maxChunkLoadDistance;

            long chunkPosLong = ChunkPos.pack(chunkX, chunkZ);
            if (!canLoad) {
                loadedOnlyChunks.add(chunkPosLong);
            } else {
                chunkFutures.add(chunkPosLong);
            }

            LongList blockEntitiesInChunk = sendBlockEntityForPendingChunks.get(chunkPosLong);
            if (blockEntitiesInChunk == null) {
                blockEntitiesInChunk = new LongArrayList();
                sendBlockEntityForPendingChunks.put(chunkPosLong, blockEntitiesInChunk);
            }
            blockEntitiesInChunk.add(pos);
        }

        for (long pos : parsed.chunkSections) {
            int sx = BlockPos.getX(pos);
            int sy = BlockPos.getY(pos);
            int sz = BlockPos.getZ(pos);

            int distance = Math.abs(playerSectionX - sx) + Math.abs(playerSectionZ - sz);
            boolean canLoad = distance < parsed.maxChunkLoadDistance;

            long chunkPosLong = ChunkPos.pack(sx, sz);
            if (!canLoad) {
                loadedOnlyChunks.add(chunkPosLong);
            } else {
                chunkFutures.add(chunkPosLong);
            }

            IntList sendSections = sendSectionsForPendingChunks.get(chunkPosLong);
            if (sendSections == null) {
                sendSections = new IntArrayList();
                sendSectionsForPendingChunks.put(chunkPosLong, sendSections);
            }
            sendSections.add(sy);
        }

        if (chunkFutures.isEmpty() && loadedOnlyChunks.isEmpty()) {
            sendResponse(player, parsed.id, sendingBlockEntities, sendingSections);
        } else {
            this.plugin.addPendingOperation(level, new RequestChunksOperation(level, player, parsed.id, chunkFutures, loadedOnlyChunks, sendBlockEntityForPendingChunks, sendSectionsForPendingChunks,
                parsed.sendBlockEntitiesInChunks, sendingSections, sendingBlockEntities, baos));
        }
    }

    public static void sendResponse(ServerPlayer player, long id, Long2ObjectOpenHashMap<CompressedBlockEntity> sendingBlockEntities,
        Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sendingSections) {
        boolean firstPart = true;
        int maxSize = 0x100000 - 64; // Leeway of 64 bytes

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeLong(id);

        var blockEntityIterator = sendingBlockEntities.long2ObjectEntrySet().fastIterator();
        while (blockEntityIterator.hasNext()) {
            Long2ObjectMap.Entry<CompressedBlockEntity> entry = blockEntityIterator.next();
            int beforeWriterIndex = buf.writerIndex();

            buf.writeLong(entry.getLongKey());
            entry.getValue().write(buf);

            if (buf.writerIndex() >= maxSize) {
                if (firstPart) {
                    // Finish and send current packet
                    buf.writeLong(AxiomConstants.MIN_POSITION_LONG);
                    buf.writeLong(AxiomConstants.MIN_POSITION_LONG);
                    buf.writeBoolean(false);
                    byte[] bytes = ByteBufUtil.getBytes(buf);
                    VersionHelper.sendCustomPayload(player, RESPONSE_ID, bytes);

                    // Continuation packet
                    buf.clear();
                    buf.writeLong(id);
                } else {
                    // Copy extra bytes
                    int copiedSize = buf.writerIndex() - beforeWriterIndex;
                    byte[] copied = new byte[copiedSize];
                    buf.getBytes(beforeWriterIndex, copied);

                    // Discard extra bytes
                    buf.writerIndex(beforeWriterIndex);

                    // Finish and send current packet
                    buf.writeLong(AxiomConstants.MIN_POSITION_LONG);
                    buf.writeLong(AxiomConstants.MIN_POSITION_LONG);
                    buf.writeBoolean(false);
                    byte[] bytes = ByteBufUtil.getBytes(buf);
                    VersionHelper.sendCustomPayload(player, RESPONSE_ID, bytes);

                    // Continuation packet
                    buf.clear();
                    buf.writeLong(id);

                    // Write start of new packet
                    buf.writeBytes(copied);
                    firstPart = true;
                }
            } else {
                firstPart = false;
            }
        }

        buf.writeLong(AxiomConstants.MIN_POSITION_LONG);

        var sectionIterator = sendingSections.long2ObjectEntrySet().fastIterator();
        while (sectionIterator.hasNext()) {
            Long2ObjectMap.Entry<PalettedContainer<BlockState>> entry = sectionIterator.next();
            int beforeWriterIndex = buf.writerIndex();

            buf.writeLong(entry.getLongKey());
            var container = entry.getValue();
            if (container == null) {
                buf.writeBoolean(false);
            } else {
                buf.writeBoolean(true);
                entry.getValue().write(buf);
            }

            if (buf.writerIndex() >= maxSize) {
                if (firstPart) {
                    // Finish and send current packet
                    buf.writeLong(AxiomConstants.MIN_POSITION_LONG);
                    buf.writeBoolean(false);
                    byte[] bytes = ByteBufUtil.getBytes(buf);
                    VersionHelper.sendCustomPayload(player, RESPONSE_ID, bytes);

                    // Continuation packet
                    buf.clear();
                    buf.writeLong(id);
                    buf.writeLong(AxiomConstants.MIN_POSITION_LONG);
                } else {
                    // Copy extra bytes
                    int copiedSize = buf.writerIndex() - beforeWriterIndex;
                    byte[] copied = new byte[copiedSize];
                    buf.getBytes(beforeWriterIndex, copied);

                    // Discard extra bytes
                    buf.writerIndex(beforeWriterIndex);

                    // Finish and send current packet
                    buf.writeLong(AxiomConstants.MIN_POSITION_LONG);
                    buf.writeBoolean(false);
                    byte[] bytes = ByteBufUtil.getBytes(buf);
                    VersionHelper.sendCustomPayload(player, RESPONSE_ID, bytes);

                    // Continuation packet
                    buf.clear();
                    buf.writeLong(id);
                    buf.writeLong(AxiomConstants.MIN_POSITION_LONG);

                    // Write start of new packet
                    buf.writeBytes(copied);
                    firstPart = true;
                }
            } else {
                firstPart = false;
            }
        }

        buf.writeLong(AxiomConstants.MIN_POSITION_LONG);
        buf.writeBoolean(true);
        byte[] bytes = ByteBufUtil.getBytes(buf);
        VersionHelper.sendCustomPayload(player, RESPONSE_ID, bytes);
    }

    private static void sendEmptyResponse(ServerPlayer player, long id) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(16));
        buf.writeLong(id);
        buf.writeLong(AxiomConstants.MIN_POSITION_LONG); // no block entities
        buf.writeLong(AxiomConstants.MIN_POSITION_LONG); // no chunks
        buf.writeBoolean(true); // finished

        byte[] bytes = ByteBufUtil.getBytes(buf);
        VersionHelper.sendCustomPayload(player, RESPONSE_ID, bytes);
    }

}
