package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.integration.plotsquared.PlotSquaredIntegration;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.world_properties.server.ServerWorldPropertiesRegistry;
import com.moulberry.axiom.world_properties.server.ServerWorldPropertyHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SetWorldPropertyListener implements PacketHandler<SetWorldPropertyListener.Parsed> {

    private final AxiomPaper plugin;
    public SetWorldPropertyListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    public record Parsed(Identifier id, int type, byte[] data, int updateId) {}

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        return this.plugin.canUseAxiom(player, AxiomPermission.WORLD_PROPERTY);
    }

    @Override
    public Parsed parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        Identifier id = friendlyByteBuf.readIdentifier();
        int type = friendlyByteBuf.readVarInt();
        byte[] data = friendlyByteBuf.readByteArray();
        int updateId = friendlyByteBuf.readVarInt();
        return new Parsed(id, type, data, updateId);
    }

    @Override
    public void apply(Player player, Parsed parsed) {
        if (parsed == null) return;

        // Call modify world
        if (!this.plugin.canModifyWorld(player, player.getWorld())) {
            sendAck(player, parsed.updateId());
            return;
        }

        // Don't allow on plot worlds
        if (PlotSquaredIntegration.isPlotWorld(player.getWorld())) {
            sendAck(player, parsed.updateId());
            return;
        }

        ServerWorldPropertiesRegistry registry = AxiomPaper.PLUGIN.getOrCreateWorldProperties(player.getWorld());
        if (registry == null) {
            sendAck(player, parsed.updateId());
            return;
        }

        ServerWorldPropertyHolder<?> property = registry.getById(parsed.id());
        if (property != null && property.getType().getTypeId() == parsed.type()) {
            if (VersionHelper.isFolia()) {
                org.bukkit.Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> {
                    property.update(player, player.getWorld(), parsed.data());
                    sendAck(player, parsed.updateId());
                });
            } else {
                property.update(player, player.getWorld(), parsed.data());
                sendAck(player, parsed.updateId());
            }
        } else {
            sendAck(player, parsed.updateId());
        }
    }

    private void sendAck(Player player, int updateId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(updateId);

        byte[] bytes = ByteBufUtil.getBytes(buf);
        VersionHelper.sendCustomPayload(player, "axiom:ack_world_properties", bytes);
    }

}

