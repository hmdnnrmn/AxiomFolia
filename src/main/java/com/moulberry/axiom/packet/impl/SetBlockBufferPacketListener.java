package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.BiomeBuffer;
import com.moulberry.axiom.buffer.BlockBuffer;
import com.moulberry.axiom.operations.SetBiomeBufferOperation;
import com.moulberry.axiom.operations.SetBlockBufferOperation;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SetBlockBufferPacketListener implements PacketHandler<SetBlockBufferPacketListener.ParsedBuffer> {

    private final AxiomPaper plugin;

    public SetBlockBufferPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handleAsync() {
        return true;
    }

    public static class ParsedBuffer {
        public final ResourceKey<Level> worldKey;
        public final byte type;
        public final BlockBuffer blockBuffer;
        public final BiomeBuffer biomeBuffer;
        public final int clientAvailableDispatchSends;

        public ParsedBuffer(ResourceKey<Level> worldKey, byte type, BlockBuffer blockBuffer, BiomeBuffer biomeBuffer, int clientAvailableDispatchSends) {
            this.worldKey = worldKey;
            this.type = type;
            this.blockBuffer = blockBuffer;
            this.biomeBuffer = biomeBuffer;
            this.clientAvailableDispatchSends = clientAvailableDispatchSends;
        }
    }

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!plugin.canUseAxiom(player, AxiomPermission.BUILD_SECTION)) {
            return false;
        }

        int readerIndex = friendlyByteBuf.readerIndex();
        try {
            ResourceKey<Level> worldKey = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
            ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();
            ServerLevel world = serverPlayer.level();
            if (!world.dimension().equals(worldKey) || !plugin.canModifyWorld(player, world.getWorld())) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            friendlyByteBuf.readerIndex(readerIndex);
        }
    }

    @Override
    public ParsedBuffer parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        ResourceKey<Level> worldKey = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        friendlyByteBuf.readUUID(); // Discard, we don't need to associate buffers

        byte type = friendlyByteBuf.readByte();
        BlockBuffer blockBuffer = null;
        BiomeBuffer biomeBuffer = null;
        if (type == 0) {
            blockBuffer = BlockBuffer.load(friendlyByteBuf, this.plugin.getBlockRegistry(playerUuid), protocolVersion);
        } else if (type == 1) {
            biomeBuffer = BiomeBuffer.load(friendlyByteBuf);
        } else {
            throw new RuntimeException("Unknown buffer type: " + type);
        }
        int clientAvailableDispatchSends = friendlyByteBuf.readVarInt();
        return new ParsedBuffer(worldKey, type, blockBuffer, biomeBuffer, clientAvailableDispatchSends);
    }

    @Override
    public void apply(Player player, ParsedBuffer parsed) {
        ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();
        ServerLevel world = serverPlayer.level();

        // Guard: player may have changed worlds between precheck and apply
        if (!world.dimension().equals(parsed.worldKey)) {
            return;
        }

        if (parsed.type == 0) {
            try {
                if (this.plugin.logLargeBlockBufferChanges()) {
                    this.plugin.getLogger().info("Player " + player.getUniqueId() + " modified " + parsed.blockBuffer.getSectionCount() + " chunk sections (blocks)");
                    if (parsed.blockBuffer.getTotalBlockEntities() > 0) {
                        this.plugin.getLogger().info("Player " + player.getUniqueId() + " modified " + parsed.blockBuffer.getTotalBlockEntities() + " block entities, compressed bytes = " +
                            parsed.blockBuffer.getTotalBlockEntityBytes());
                    }
                }

                if (!this.plugin.consumeDispatchSends(player, parsed.blockBuffer.getSectionCount(), parsed.clientAvailableDispatchSends)) {
                    return;
                }

                boolean allowNbt = this.plugin.hasPermission(player, AxiomPermission.BUILD_NBT);
                this.plugin.addPendingOperation(world, new SetBlockBufferOperation(serverPlayer, parsed.blockBuffer, allowNbt));
            } catch (Throwable t) {
                player.kick(net.kyori.adventure.text.Component.text("An error occured while processing block change: " + t.getMessage()));
            }
        } else if (parsed.type == 1) {
            try {
                if (this.plugin.logLargeBlockBufferChanges()) {
                    this.plugin.getLogger().info("Player " + player.getUniqueId() + " modified " + parsed.biomeBuffer.getSectionCount() + " chunk sections (biomes)");
                }

                if (!this.plugin.consumeDispatchSends(player, parsed.biomeBuffer.getSectionCount(), parsed.clientAvailableDispatchSends)) {
                    return;
                }

                this.plugin.addPendingOperation(world, new SetBiomeBufferOperation(serverPlayer, parsed.biomeBuffer));
            } catch (Throwable t) {
                player.kick(net.kyori.adventure.text.Component.text("An error occured while processing biome change: " + t.getMessage()));
            }
        }
    }
}
