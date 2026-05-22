package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.annotations.AnnotationUpdateAction;
import com.moulberry.axiom.annotations.ServerAnnotations;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UpdateAnnotationPacketListener implements PacketHandler<List<AnnotationUpdateAction>> {

    private final AxiomPaper plugin;
    public UpdateAnnotationPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.allowAnnotations || !this.plugin.canUseAxiom(player, AxiomPermission.ANNOTATION_CREATE)) {
            return false;
        }

        if (!this.plugin.canModifyWorld(player, player.getWorld())) {
            return false;
        }

        return true;
    }

    @Override
    public List<AnnotationUpdateAction> parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        int length = friendlyByteBuf.readVarInt();
        List<AnnotationUpdateAction> actions = new ArrayList<>(Math.min(256, length));
        for (int i = 0; i < length; i++) {
            AnnotationUpdateAction action = AnnotationUpdateAction.read(friendlyByteBuf);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    @Override
    public void apply(Player player, List<AnnotationUpdateAction> actions) {
        ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();

        if (VersionHelper.isFolia()) {
            org.bukkit.Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> {
                try {
                    ServerAnnotations.handleUpdates(serverPlayer.level().getWorld(), actions);
                } catch (Throwable t) {
                    player.kick(net.kyori.adventure.text.Component.text(
                            "An error occured while updating annotations: " + t.getMessage()));
                }
            });
        } else {
            serverPlayer.level().getServer().execute(() -> {
                try {
                    ServerAnnotations.handleUpdates(serverPlayer.level().getWorld(), actions);
                } catch (Throwable t) {
                    player.kick(net.kyori.adventure.text.Component.text(
                            "An error occured while updating annotations: " + t.getMessage()));
                }
            });
        }
    }

}

