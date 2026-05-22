package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.event.AxiomTimeChangeEvent;
import com.moulberry.axiom.integration.plotsquared.PlotSquaredIntegration;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SetTimePacketListener implements PacketHandler<SetTimePacketListener.Parsed> {

    private final AxiomPaper plugin;
    public SetTimePacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    public record Parsed(ResourceKey<Level> key, Integer time, Boolean freezeTime) {}

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.canUseAxiom(player, AxiomPermission.WORLD_TIME)) {
            return false;
        }

        // Don't allow on plot worlds
        if (PlotSquaredIntegration.isPlotWorld(player.getWorld())) {
            return false;
        }

        // Call modify world
        if (!this.plugin.canModifyWorld(player, player.getWorld())) {
            return false;
        }

        return true;
    }

    @Override
    public Parsed parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        ResourceKey<Level> key = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        Integer time = friendlyByteBuf.readNullable(FriendlyByteBuf::readInt);
        Boolean freezeTime = friendlyByteBuf.readNullable(FriendlyByteBuf::readBoolean);
        return new Parsed(key, time, freezeTime);
    }

    @Override
    public void apply(Player player, Parsed parsed) {
        if (parsed == null) return;
        if (parsed.time() == null && parsed.freezeTime() == null) return;

        ServerLevel level = ((CraftWorld)player.getWorld()).getHandle();
        if (!level.dimension().equals(parsed.key())) return;

        // Call time change event
        AxiomTimeChangeEvent timeChangeEvent = new AxiomTimeChangeEvent(player, parsed.time(), parsed.freezeTime());
        Bukkit.getPluginManager().callEvent(timeChangeEvent);
        if (timeChangeEvent.isCancelled()) return;

        // Change time
        if (VersionHelper.isFolia()) {
            Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> {
                if (parsed.time() != null) player.getWorld().setTime(parsed.time());
                if (parsed.freezeTime() != null) level.getGameRules().set(GameRules.ADVANCE_TIME, !parsed.freezeTime(), null);
            });
        } else {
            if (parsed.time() != null) player.getWorld().setTime(parsed.time());
            if (parsed.freezeTime() != null) level.getGameRules().set(GameRules.ADVANCE_TIME, !parsed.freezeTime(), null);
        }
    }

}
