package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.event.AxiomFlySpeedChangeEvent;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SetFlySpeedPacketListener implements PacketHandler<Float> {

    private final AxiomPaper plugin;
    public SetFlySpeedPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        return this.plugin.canUseAxiom(player, AxiomPermission.PLAYER_SPEED);
    }

    @Override
    public Float parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        float flySpeed = friendlyByteBuf.readFloat();
        return Math.max(-1.0f, Math.min(1.0f, flySpeed));
    }

    @Override
    public void apply(Player player, Float flySpeed) {
        // Call event
        AxiomFlySpeedChangeEvent flySpeedChangeEvent = new AxiomFlySpeedChangeEvent(player, flySpeed);
        Bukkit.getPluginManager().callEvent(flySpeedChangeEvent);
        if (flySpeedChangeEvent.isCancelled()) return;

        // Change flying speed
        ((CraftPlayer)player).getHandle().getAbilities().setFlyingSpeed(flySpeed);
    }

}

