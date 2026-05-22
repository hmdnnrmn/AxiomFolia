package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RequestEntityDataPacketListener implements PacketHandler<RequestEntityDataPacketListener.Parsed> {

    public static final Identifier RESPONSE_ID = VersionHelper.createIdentifier("axiom:response_entity_data");

    private final AxiomPaper plugin;
    public RequestEntityDataPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    public record Parsed(long id, List<UUID> entities) {}

    @Override
    public Parsed parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        long id = friendlyByteBuf.readLong();
        List<UUID> entities = friendlyByteBuf.readCollection(this.plugin.limitCollection(ArrayList::new), buf -> buf.readUUID());
        return new Parsed(id, entities);
    }

    @Override
    public void apply(Player bukkitPlayer, Parsed parsed) {
        if (parsed == null) return;

        ServerPlayer player = ((CraftPlayer)bukkitPlayer).getHandle();

        if (!this.plugin.canUseAxiom(bukkitPlayer, AxiomPermission.ENTITY_REQUESTDATA) || this.plugin.isMismatchedDataVersion(bukkitPlayer.getUniqueId())) {
            // We always send an 'empty' response in order to make the client happy
            sendResponse(player, parsed.id(), true, Map.of());
            return;
        }

        if (!this.plugin.canModifyWorld(bukkitPlayer, bukkitPlayer.getWorld())) {
            sendResponse(player, parsed.id(), true, Map.of());
            return;
        }

        if (parsed.entities().isEmpty()) {
            sendResponse(player, parsed.id(), true, Map.of());
            return;
        }

        List<CompletableFuture<Map.Entry<UUID, CompoundTag>>> futures = new ArrayList<>();
        Set<UUID> visitedEntities = new HashSet<>();

        for (UUID uuid : parsed.entities()) {
            if (!visitedEntities.add(uuid)) {
                continue;
            }

            org.bukkit.entity.Entity bukkitEntity = Bukkit.getEntity(uuid);
            if (bukkitEntity == null || !bukkitEntity.getWorld().equals(bukkitPlayer.getWorld())) {
                continue;
            }

            CompletableFuture<Map.Entry<UUID, CompoundTag>> future = new CompletableFuture<>();
            futures.add(future);

            if (VersionHelper.isFolia()) {
                bukkitEntity.getScheduler().run(this.plugin, task -> {
                    try {
                        Map.Entry<UUID, CompoundTag> entry = getEntityData(bukkitPlayer, bukkitEntity, uuid);
                        future.complete(entry);
                    } catch (Throwable t) {
                        future.complete(null);
                    }
                }, () -> future.complete(null));
            } else {
                try {
                    Map.Entry<UUID, CompoundTag> entry = getEntityData(bukkitPlayer, bukkitEntity, uuid);
                    future.complete(entry);
                } catch (Throwable t) {
                    future.complete(null);
                }
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRunAsync(() -> {
            Map<UUID, CompoundTag> entityData = new HashMap<>();
            final int maxPacketSize = 0x100000;
            int remainingBytes = maxPacketSize;

            for (CompletableFuture<Map.Entry<UUID, CompoundTag>> future : futures) {
                Map.Entry<UUID, CompoundTag> entry = future.join();
                if (entry != null && entry.getValue() != null) {
                    UUID uuid = entry.getKey();
                    CompoundTag entityTag = entry.getValue();
                    int size = entityTag.sizeInBytes();

                    if (size >= maxPacketSize) {
                        sendResponse(player, parsed.id(), false, Map.of(uuid, entityTag));
                        continue;
                    }

                    if (remainingBytes - size < 0) {
                        sendResponse(player, parsed.id(), false, entityData);
                        entityData.clear();
                        remainingBytes = maxPacketSize;
                    }

                    entityData.put(uuid, entityTag);
                    remainingBytes -= size;
                }
            }

            sendResponse(player, parsed.id(), true, entityData);
        }, this.plugin.getAsyncExecutor());
    }

    private Map.Entry<UUID, CompoundTag> getEntityData(Player bukkitPlayer, org.bukkit.entity.Entity bukkitEntity, UUID uuid) {
        if (!bukkitEntity.isValid()) return null;
        Entity entity = ((org.bukkit.craftbukkit.entity.CraftEntity)bukkitEntity).getHandle();
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            return null;
        }

        if (!this.plugin.canEntityBeManipulated(entity.getType())) {
            return null;
        }

        if (!Integration.canPlaceBlock(bukkitPlayer, new Location(bukkitPlayer.getWorld(),
                entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()))) {
            return null;
        }

        var valueOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        var entityTag = entity.save(valueOutput) ? valueOutput.buildResult() : null;
        if (entityTag != null) {
            return Map.entry(uuid, entityTag);
        }
        return null;
    }

    private static void sendResponse(ServerPlayer player, long id, boolean finished, Map<UUID, CompoundTag> map) {
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        friendlyByteBuf.writeLong(id);
        friendlyByteBuf.writeBoolean(finished);
        friendlyByteBuf.writeMap(map, (buf, uuid) -> buf.writeUUID(uuid), (buf, nbt) -> buf.writeNbt(nbt));

        byte[] bytes = ByteBufUtil.getBytes(friendlyByteBuf);
        VersionHelper.sendCustomPayload(player, RESPONSE_ID, bytes);
    }

}

