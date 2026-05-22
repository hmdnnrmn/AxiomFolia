package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.event.AxiomUnknownTeleportEvent;
import com.moulberry.axiom.event.AxiomTeleportEvent;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.*;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.entity.Player;

import java.util.UUID;

public class TeleportPacketListener implements PacketHandler<TeleportPacketListener.Parsed> {

    private final AxiomPaper plugin;
    public TeleportPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    public record Parsed(ResourceKey<Level> resourceKey, double x, double y, double z, float yRot, float xRot) {}

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        return this.plugin.canUseAxiom(player, AxiomPermission.PLAYER_TELEPORT);
    }

    @Override
    public Parsed parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        ResourceKey<Level> resourceKey = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        double x = friendlyByteBuf.readDouble();
        double y = friendlyByteBuf.readDouble();
        double z = friendlyByteBuf.readDouble();
        float yRot = friendlyByteBuf.readFloat();
        float xRot = friendlyByteBuf.readFloat();
        return new Parsed(resourceKey, x, y, z, yRot, xRot);
    }

    @Override
    public void apply(Player player, Parsed parsed) {
        if (parsed == null) return;

        // Prevent teleport based on config value
        boolean allowTeleportBetweenWorlds = this.plugin.configuration.getBoolean("allow-teleport-between-worlds");
        if (!allowTeleportBetweenWorlds && !((CraftPlayer)player).getHandle().level().dimension().equals(parsed.resourceKey())) {
            return;
        }

        // Call unknown teleport event
        AxiomUnknownTeleportEvent preTeleportEvent = new AxiomUnknownTeleportEvent(player,
                CraftNamespacedKey.fromMinecraft(parsed.resourceKey().identifier()), parsed.x(), parsed.y(), parsed.z(), parsed.yRot(), parsed.xRot());
        Bukkit.getPluginManager().callEvent(preTeleportEvent);
        if (preTeleportEvent.isCancelled()) return;

        // Get bukkit world
        NamespacedKey namespacedKey = new NamespacedKey(parsed.resourceKey().identifier().getNamespace(), parsed.resourceKey().identifier().getPath());
        World world = Bukkit.getWorld(namespacedKey);
        if (world == null) return;

        // Prevent teleport based on config value
        if (!allowTeleportBetweenWorlds && world != player.getWorld()) {
            return;
        }

        // Call event
        AxiomTeleportEvent teleportEvent = new AxiomTeleportEvent(player, new Location(world, parsed.x(), parsed.y(), parsed.z(), parsed.yRot(), parsed.xRot()));
        Bukkit.getPluginManager().callEvent(teleportEvent);
        if (teleportEvent.isCancelled()) return;

        // Do teleport
        if (VersionHelper.isFolia()) {
            player.teleportAsync(new Location(world, parsed.x(), parsed.y(), parsed.z(), parsed.yRot(), parsed.xRot()));
        } else {
            player.teleport(new Location(world, parsed.x(), parsed.y(), parsed.z(), parsed.yRot(), parsed.xRot()));
        }
    }

}

