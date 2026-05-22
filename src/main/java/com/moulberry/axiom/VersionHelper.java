package com.moulberry.axiom;

import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.IdMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

public class VersionHelper {

    @FunctionalInterface
    private interface DiscardedPayloadConstructor {
        DiscardedPayload create(Identifier id, byte[] data) throws IllegalAccessException, InstantiationException, InvocationTargetException;
    }
    private static DiscardedPayloadConstructor discardedPayloadConstructor = null;

    public static DiscardedPayload createCustomPayload(Identifier id, byte[] data) {
        if (discardedPayloadConstructor == null) {
            findDiscardedPayloadConstructor();
        }
        try {
            return discardedPayloadConstructor.create(id, data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void findDiscardedPayloadConstructor() {
        for (Constructor<?> ctor : DiscardedPayload.class.getConstructors()) {
            var parameters = ctor.getParameters();
            if (parameters.length == 2) {
                var parameter1 = parameters[0].getType();
                if (Identifier.class.isAssignableFrom(parameter1)) {
                    var parameter2 = parameters[1].getType();
                    if (byte[].class.isAssignableFrom(parameter2)) {
                        discardedPayloadConstructor = (id1, data1) -> (DiscardedPayload) ctor.newInstance(id1, data1);
                        break;
                    } else if (io.netty.buffer.ByteBuf.class.isAssignableFrom(parameter2)) {
                        discardedPayloadConstructor = (id1, data1) -> (DiscardedPayload) ctor.newInstance(id1, Unpooled.wrappedBuffer(data1));
                        break;
                    }
                }
            }
        }
        if (discardedPayloadConstructor == null) {
            throw new RuntimeException("Unable to find suitable DiscardedPayload constructor");
        }
    }

    private static Boolean isFolia = null;
    public static boolean isFolia() {
        if (isFolia == null) {
            try {
                Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
                isFolia = true;
            } catch (ClassNotFoundException e) {
                isFolia = false;
            }
        }
        return isFolia;
    }

    public static void sendCustomPayload(Player player, String id, byte[] data) {
        sendCustomPayload(((CraftPlayer) player).getHandle(), id, data);
    }

    public static void sendCustomPayload(ServerPlayer serverPlayer, String id, byte[] data) {
        sendCustomPayload(serverPlayer, createIdentifier(id), data);
    }

    public static void sendCustomPayload(ServerPlayer serverPlayer, Identifier id, byte[] data) {
        sendCustomPayload(serverPlayer, id, data, null);
    }

    public static void sendCustomPayload(ServerPlayer serverPlayer, Identifier id, byte[] data, Runnable retiredRunnable) {
        if (isFolia()) {
            var bukkitPlayer = serverPlayer.getBukkitEntity();
            bukkitPlayer.getScheduler().run(AxiomPaper.PLUGIN, task -> {
                var payload = createCustomPayload(id, data);
                serverPlayer.connection.send(new ClientboundCustomPayloadPacket(payload));
            }, retiredRunnable);
        } else {
            if (serverPlayer.getBukkitEntity().isOnline()) {
                var payload = createCustomPayload(id, data);
                serverPlayer.connection.send(new ClientboundCustomPayloadPacket(payload));
            } else if (retiredRunnable != null) {
                retiredRunnable.run();
            }
        }
    }

    public static void sendCustomPayloadToAll(List<ServerPlayer> players, String id, byte[] data) {
        sendCustomPayloadToAll(players, createIdentifier(id), data);
    }

    public static void sendCustomPayloadToAll(List<ServerPlayer> players, Identifier id, byte[] data) {
        if (players.isEmpty()) {
            return;
        }

        if (isFolia()) {
            for (ServerPlayer player : players) {
                sendCustomPayload(player, id, data);
            }
        } else {
            var payload = createCustomPayload(id, data);
            var packet = new ClientboundCustomPayloadPacket(payload);
            for (ServerPlayer player : players) {
                player.connection.send(packet);
            }
        }
    }

    public static Identifier createIdentifier(String composed) {
        return Identifier.parse(composed);
    }

    public static Identifier createIdentifier(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    public static ListTag getList(CompoundTag tag, String key, int type) {
        ListTag list = tag.getListOrEmpty(key);
        if (list.isEmpty()) {
            return list;
        }
        if (list.get(0).getId() != type) {
            return new ListTag();
        } else {
            return list;
        }
    }

    public static PalettedContainer<BlockState> createPalettedContainer(IdMap<BlockState> map, BlockState defaultValue) {
        return new PalettedContainer<>(defaultValue, Strategy.createForBlockStates(map));
    }

    public static Codec<PalettedContainer<BlockState>> createPalettedContainerCodec(Codec<BlockState> codec, IdMap<BlockState> map, BlockState defaultValue) {
        return PalettedContainer.codecRW(codec, Strategy.createForBlockStates(map), defaultValue);
    }

    public static String getVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

}
