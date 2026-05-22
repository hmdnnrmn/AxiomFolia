package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.event.AxiomGameModeChangeEvent;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SetGamemodePacketListener implements PacketHandler<GameType> {

    private final AxiomPaper plugin;
    public SetGamemodePacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public GameType parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        return GameType.byId(friendlyByteBuf.readByte());
    }

    @Override
    public void apply(Player player, GameType gameType) {
        AxiomPermission permission = switch (gameType) {
            case SURVIVAL -> AxiomPermission.PLAYER_GAMEMODE_SURVIVAL;
            case CREATIVE -> AxiomPermission.PLAYER_GAMEMODE_CREATIVE;
            case ADVENTURE -> AxiomPermission.PLAYER_GAMEMODE_ADVENTURE;
            case SPECTATOR -> AxiomPermission.PLAYER_GAMEMODE_SPECTATOR;
            default -> AxiomPermission.PLAYER_GAMEMODE;
        };

        if (!this.plugin.canUseAxiom(player, permission)) {
            return;
        }

        // Call event
        AxiomGameModeChangeEvent gameModeChangeEvent = new AxiomGameModeChangeEvent(player, GameMode.getByValue(gameType.getId()));
        Bukkit.getPluginManager().callEvent(gameModeChangeEvent);
        if (gameModeChangeEvent.isCancelled()) return;

        // Change gamemode
        ((CraftPlayer)player).getHandle().setGameMode(gameType);
    }

}

