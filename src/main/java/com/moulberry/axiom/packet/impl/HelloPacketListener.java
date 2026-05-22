package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.*;
import com.moulberry.axiom.blueprint.DFUHelper;
import com.moulberry.axiom.blueprint.ServerBlueprintManager;
import com.moulberry.axiom.event.AxiomHandshakeEvent;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.paperapi.display.ImplServerCustomDisplays;
import com.moulberry.axiom.paperapi.entity.ImplAxiomHiddenEntities;
import com.moulberry.axiom.paperapi.block.ImplServerCustomBlocks;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.viaversion.ViaVersionHelper;
import com.moulberry.axiom.world_properties.server.ServerWorldPropertiesRegistry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.SharedConstants;
import net.minecraft.core.IdMapper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public class HelloPacketListener implements PacketHandler<HelloPacketListener.Parsed> {

    private final AxiomPaper plugin;

    public HelloPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    public record Parsed(int apiVersion, int dataVersion, int protocolVersion) {}

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.hasPermission(player, AxiomPermission.USE)) {
            this.plugin.failedPermissionAxiomPlayers.add(player.getUniqueId());
            return false;
        }
        return true;
    }

    @Override
    public Parsed parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        int apiVersion = friendlyByteBuf.readVarInt();
        int dataVersion = friendlyByteBuf.readVarInt();
        int readProtocolVersion = friendlyByteBuf.readVarInt();
        return new Parsed(apiVersion, dataVersion, readProtocolVersion);
    }

    @Override
    public void apply(Player player, Parsed parsed) {
        if (parsed == null) return;

        if (parsed.apiVersion() != AxiomConstants.API_VERSION) {
            String versions = " (C="+parsed.apiVersion()+" S="+AxiomConstants.API_VERSION+")";
            Component text;
            if (parsed.apiVersion() < AxiomConstants.API_VERSION) {
                text = Component.text("Unable to use Axiom, you're on an outdated version! Please update to the latest version of Axiom to use it on this server." + versions);
            } else {
                text = Component.text("Unable to use Axiom, server hasn't updated Axiom yet." + versions);
            }

            String unsupportedAxiomVersion = plugin.configuration.getString("unsupported-axiom-version");
            if (unsupportedAxiomVersion == null) unsupportedAxiomVersion = "kick";
            if (unsupportedAxiomVersion.equals("warn")) {
                player.sendMessage(text.color(NamedTextColor.RED));
                return;
            } else if (!unsupportedAxiomVersion.equals("ignore")) {
                player.kick(text);
                return;
            }
        }

        int serverDataVersion = DFUHelper.DATA_VERSION;
        if (parsed.protocolVersion() != SharedConstants.getProtocolVersion()) {
            String incompatibleDataVersion = plugin.configuration.getString("incompatible-data-version");
            if (incompatibleDataVersion == null) incompatibleDataVersion = "warn";

            Component incompatibleWarning = Component.text("Axiom: Incompatible data version detected (client " + parsed.dataVersion() +
                ", server " + serverDataVersion  + ")");

            if (!Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) {
                if (incompatibleDataVersion.equals("warn")) {
                    player.sendMessage(incompatibleWarning.color(NamedTextColor.RED));
                    return;
                } else if (!incompatibleDataVersion.equals("ignore")) {
                    player.kick(incompatibleWarning);
                    return;
                }
            } else {
                IdMapper<BlockState> mapper;
                try {
                    mapper = ViaVersionHelper.getBlockRegistryForVersion(this.plugin.allowedBlockRegistry, parsed.protocolVersion());
                } catch (Exception e) {
                    String clientDescription = "client: " + ProtocolVersion.getProtocol(parsed.protocolVersion());
                    String serverDescription = "server: " + ProtocolVersion.getProtocol(SharedConstants.getProtocolVersion());
                    String description = clientDescription + " <-> " + serverDescription;
                    Component text = Component.text("Axiom+ViaVersion: " + e.getMessage() + " (" + description + ")");

                    if (incompatibleDataVersion.equals("warn")) {
                        player.sendMessage(text.color(NamedTextColor.RED));
                    } else {
                        player.kick(text);
                    }
                    return;
                }

                this.plugin.playerBlockRegistry.put(player.getUniqueId(), mapper);
                this.plugin.playerProtocolVersion.put(player.getUniqueId(), parsed.protocolVersion());

                Component text = Component.text("Axiom: Warning, client and server versions don't match. " +
                        "Axiom will try to use ViaVersion conversions, but this process may cause problems");
                player.sendMessage(text.color(NamedTextColor.RED));
            }
        }

        // Call handshake event
        int maxBufferSize = this.plugin.configuration.getInt("max-block-buffer-packet-size");
        AxiomHandshakeEvent handshakeEvent = new AxiomHandshakeEvent(player, maxBufferSize);
        Bukkit.getPluginManager().callEvent(handshakeEvent);
        if (handshakeEvent.isCancelled()) {
            return;
        }

        // Enable
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), MinecraftServer.getServer().registryAccess());
        buf.writeBoolean(true);
        buf.writeInt(handshakeEvent.getMaxBufferSize()); // Max Buffer Size
        buf.writeVarInt(2); // Blueprint version
        buf.writeVarInt(0); // No custom data overrides
        buf.writeVarInt(0); // No rotation overrides

        byte[] enableBytes = ByteBufUtil.getBytes(buf);
        VersionHelper.sendCustomPayload(player, "axiom:enable", enableBytes);

        this.plugin.onAxiomActive(player);

        // Register world properties
        World world = player.getWorld();
        ServerWorldPropertiesRegistry properties = plugin.getOrCreateWorldProperties(world);

        if (properties == null) {
            VersionHelper.sendCustomPayload(player, "axiom:register_world_properties", new byte[]{0});
        } else {
            properties.registerFor(plugin, player);
        }

        WorldExtension.onPlayerJoin(world, player);

        ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();
        ServerBlueprintManager.sendManifest(List.of(serverPlayer));

        ServerHeightmaps.sendTo(player);
        ImplServerCustomBlocks.sendAll(serverPlayer);
        ImplServerCustomDisplays.sendAll(serverPlayer);
        ImplAxiomHiddenEntities.sendAll(List.of(serverPlayer));

        if (!player.isOp() && !player.hasPermission("*") && player.hasPermission("axiom.*")) {
            Component text = Component.text("Axiom: Using deprecated axiom.* permission. Please switch to axiom.default for public servers, or axiom.all for private servers");
            player.sendMessage(text.color(NamedTextColor.YELLOW));
        }

        if (player.isOp() && (this.plugin.configAddedEntries != 0 || this.plugin.configRemovedEntries != 0)) {
            StringBuilder builder = new StringBuilder("Axiom: Plugin config is outdated (");
            if (this.plugin.configAddedEntries != 0) {
                builder.append(this.plugin.configAddedEntries).append(" new entries");
            }
            if (this.plugin.configRemovedEntries != 0) {
                if (this.plugin.configAddedEntries != 0) {
                    builder.append(", ");
                }
                builder.append(this.plugin.configRemovedEntries).append(" removed entries");
            }
            builder.append(")");

            Component text = Component.text(builder.toString());
            player.sendMessage(text.color(NamedTextColor.YELLOW));

            Component click = Component.text("CLICK HERE").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                                       .hoverEvent(Component.text("/axiompapermigrateconfig"))
                                       .clickEvent(ClickEvent.runCommand("axiompapermigrateconfig"));
            player.sendMessage(Component.text().append(click).append(Component.text(" to migrate the config").color(NamedTextColor.YELLOW)));
        }
    }

}

