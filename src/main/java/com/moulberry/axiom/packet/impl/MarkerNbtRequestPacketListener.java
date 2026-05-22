package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.marker.MarkerData;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.Marker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public class MarkerNbtRequestPacketListener implements PacketHandler<MarkerNbtRequestPacketListener.Parsed> {

    private final AxiomPaper plugin;
    public MarkerNbtRequestPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    public record Parsed(UUID uuid, int reason) {}

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.canUseAxiom(player, AxiomPermission.ENTITY_REQUESTDATA)) {
            return false;
        }

        if (!this.plugin.canModifyWorld(player, player.getWorld())) {
            return false;
        }

        return true;
    }

    @Override
    public Parsed parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        UUID uuid = friendlyByteBuf.readUUID();
        int reason = friendlyByteBuf.readVarInt();
        return new Parsed(uuid, reason);
    }

    @Override
    public void apply(Player player, Parsed parsed) {
        if (parsed == null) return;

        org.bukkit.entity.Entity bukkitEntity = Bukkit.getEntity(parsed.uuid());
        if (bukkitEntity != null && bukkitEntity.getWorld().equals(player.getWorld())) {
            if (VersionHelper.isFolia()) {
                bukkitEntity.getScheduler().run(this.plugin, task -> {
                    sendMarkerNbt(player, bukkitEntity, parsed.uuid());
                }, null);
            } else {
                sendMarkerNbt(player, bukkitEntity, parsed.uuid());
            }
        }
    }

    private void sendMarkerNbt(Player player, org.bukkit.entity.Entity bukkitEntity, UUID uuid) {
        if (!bukkitEntity.isValid()) return;
        net.minecraft.world.entity.Entity entity = ((org.bukkit.craftbukkit.entity.CraftEntity)bukkitEntity).getHandle();
        if (entity instanceof Marker marker) {
            CompoundTag data = MarkerData.getData(marker);

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUUID(uuid);
            buf.writeNbt(data);

            byte[] bytes = ByteBufUtil.getBytes(buf);
            VersionHelper.sendCustomPayload(player, "axiom:marker_nbt_response", bytes);
        }
    }

}

