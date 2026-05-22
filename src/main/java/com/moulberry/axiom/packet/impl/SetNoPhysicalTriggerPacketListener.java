package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SetNoPhysicalTriggerPacketListener implements PacketHandler<Boolean> {

    private final AxiomPaper plugin;
    public SetNoPhysicalTriggerPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        return this.plugin.canUseAxiom(player, AxiomPermission.PLAYER_SETNOPHYSICALTRIGGER);
    }

    @Override
    public Boolean parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        return friendlyByteBuf.readBoolean();
    }

    @Override
    public void apply(Player player, Boolean value) {
        this.plugin.setNoPhysicalTrigger(player.getUniqueId(), value);
    }

}

