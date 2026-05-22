package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.blueprint.RawBlueprint;
import com.moulberry.axiom.blueprint.ServerBlueprintManager;
import com.moulberry.axiom.blueprint.ServerBlueprintRegistry;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public class BlueprintRequestPacketListener implements PacketHandler<String> {

    private final AxiomPaper plugin;
    public BlueprintRequestPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    private static final Identifier RESPONSE_PACKET_IDENTIFIER = VersionHelper.createIdentifier("axiom:response_blueprint");

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.canUseAxiom(player, AxiomPermission.BLUEPRINT_REQUEST)) {
            return false;
        }

        if (this.plugin.isMismatchedDataVersion(player.getUniqueId())) {
            player.sendMessage(Component.text("Axiom+ViaVersion: This feature isn't supported. Switch your client version to " + VersionHelper.getVersion() + " to use this"));
            return false;
        }
        return true;
    }

    @Override
    public String parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        return friendlyByteBuf.readUtf();
    }

    @Override
    public void apply(Player player, String path) {
        ServerBlueprintRegistry registry = ServerBlueprintManager.getRegistry();
        if (registry == null) {
            return;
        }

        RawBlueprint rawBlueprint;
        synchronized (ServerBlueprintManager.class) {
            rawBlueprint = registry.blueprints().get(path);
        }

        if (rawBlueprint != null) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

            buf.writeUtf(path);
            RawBlueprint.write(buf, rawBlueprint);

            byte[] bytes = ByteBufUtil.getBytes(buf);
            VersionHelper.sendCustomPayload(((CraftPlayer)player).getHandle(), RESPONSE_PACKET_IDENTIFIER, bytes);
        }
    }

}

