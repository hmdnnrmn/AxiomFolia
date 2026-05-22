package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.event.AxiomRemoveEntityEvent;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DeleteEntityPacketListener implements PacketHandler<List<UUID>> {

    private final AxiomPaper plugin;
    public DeleteEntityPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.canUseAxiom(player, AxiomPermission.ENTITY_DELETE)) {
            return false;
        }

        if (!this.plugin.canModifyWorld(player, player.getWorld())) {
            return false;
        }

        return true;
    }

    @Override
    public List<UUID> parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        return friendlyByteBuf.readCollection(this.plugin.limitCollection(ArrayList::new), buf -> buf.readUUID());
    }

    @Override
    public void apply(Player player, List<UUID> delete) {
        if (delete == null) return;

        for (UUID uuid : delete) {
            org.bukkit.entity.Entity bukkitEntity = Bukkit.getEntity(uuid);
            if (bukkitEntity != null && bukkitEntity.getWorld().equals(player.getWorld())) {
                if (VersionHelper.isFolia()) {
                    bukkitEntity.getScheduler().run(this.plugin, task -> {
                        deleteEntity(player, bukkitEntity);
                    }, null);
                } else {
                    deleteEntity(player, bukkitEntity);
                }
            }
        }
    }

    private void deleteEntity(Player player, org.bukkit.entity.Entity bukkitEntity) {
        if (!bukkitEntity.isValid()) return;
        Entity entity = ((org.bukkit.craftbukkit.entity.CraftEntity)bukkitEntity).getHandle();
        if (entity instanceof net.minecraft.world.entity.player.Player || entity.hasPassenger(e -> e instanceof net.minecraft.world.entity.player.Player)) {
            return;
        }

        if (!this.plugin.canEntityBeManipulated(entity.getType())) {
            return;
        }

        if (!Integration.canBreakBlock(player,
                player.getWorld().getBlockAt(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()))) {
            return;
        }

        AxiomRemoveEntityEvent removeEntityEvent = new AxiomRemoveEntityEvent(player, bukkitEntity);
        Bukkit.getPluginManager().callEvent(removeEntityEvent);

        if (!removeEntityEvent.isCancelled()) {
            entity.remove(Entity.RemovalReason.DISCARDED);
        }
    }

}

