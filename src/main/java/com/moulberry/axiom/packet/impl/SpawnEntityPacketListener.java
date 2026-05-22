package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.NbtSanitization;
import com.moulberry.axiom.event.AxiomSpawnEntityEvent;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.viaversion.UnknownVersionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class SpawnEntityPacketListener implements PacketHandler<List<SpawnEntityPacketListener.SpawnEntry>> {

    private final AxiomPaper plugin;
    public SpawnEntityPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    public record SpawnEntry(UUID newUuid, double x, double y, double z, float yaw, float pitch,
                             @Nullable UUID copyFrom, CompoundTag tag) {
    }

    private static final Rotation[] ROTATION_VALUES = Rotation.values();

    @Override
    public boolean handleAsync() {
        return true;
    }

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        return plugin.canUseAxiom(player, AxiomPermission.ENTITY_SPAWN) && plugin.canModifyWorld(player, player.getWorld());
    }

    @Override
    public List<SpawnEntry> parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        return friendlyByteBuf.readCollection(this.plugin.limitCollection(ArrayList::new),
            buf -> new SpawnEntry(buf.readUUID(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readFloat(), buf.readFloat(),
                buf.readNullable(buffer -> buffer.readUUID()), UnknownVersionHelper.readTagUnknown(buf, protocolVersion)));
    }

    @Override
    public void apply(Player player, List<SpawnEntry> parsed) {
        for (SpawnEntry entry : parsed) {
            java.util.function.Consumer<CompoundTag> spawnTask = (tagToUse) -> {
                int targetX = BlockPos.containing(entry.x, entry.y, entry.z).getX() >> 4;
                int targetZ = BlockPos.containing(entry.x, entry.y, entry.z).getZ() >> 4;

                org.bukkit.World bukkitWorld = player.getWorld();
                java.util.function.Consumer<Void> doSpawn = (ignored) -> {
                    ServerLevel serverLevel = ((CraftWorld)bukkitWorld).getHandle();
                    Vec3 position = new Vec3(entry.x, entry.y, entry.z);
                    BlockPos blockPos = BlockPos.containing(position);
                    if (!Level.isInSpawnableBounds(blockPos)) {
                        return;
                    }

                    if (!Integration.canPlaceBlock(player, new Location(bukkitWorld,
                            blockPos.getX(), blockPos.getY(), blockPos.getZ()))) {
                        return;
                    }

                    if (serverLevel.getEntity(entry.newUuid) != null) return;

                    CompoundTag tag = tagToUse == null ? new CompoundTag() : tagToUse;
                    NbtSanitization.sanitizeEntity(tag);

                    if (!tag.contains("id")) return;

                    AtomicBoolean useNewUuid = new AtomicBoolean(true);

                    Entity spawned = EntityType.loadEntityRecursive(tag, serverLevel, EntitySpawnReason.COMMAND, entity -> {
                        if (!this.plugin.canEntityBeManipulated(entity.getType())) {
                            return null;
                        }

                        if (useNewUuid.getAndSet(false)) {
                            entity.setUUID(entry.newUuid);
                        } else {
                            entity.setUUID(UUID.randomUUID());
                        }

                        if (entity instanceof HangingEntity hangingEntity) {
                            float changedYaw = entry.yaw - entity.getYRot();
                            int rotations = Math.round(changedYaw / 90);
                            hangingEntity.rotate(ROTATION_VALUES[rotations & 3]);

                            if (entity instanceof ItemFrame itemFrame && itemFrame.getDirection().getAxis() == Direction.Axis.Y) {
                                itemFrame.setRotation(itemFrame.getRotation() - Math.round(changedYaw / 45));
                            }
                        }

                        entity.snapTo(position.x, position.y, position.z, entry.yaw, entry.pitch);
                        entity.setYHeadRot(entity.getYRot());

                        return entity;
                    });

                    if (spawned != null) {
                        if (serverLevel.tryAddFreshEntityWithPassengers(spawned)) {
                            AxiomSpawnEntityEvent spawnEntityEvent = new AxiomSpawnEntityEvent(player, spawned.getBukkitEntity());
                            Bukkit.getPluginManager().callEvent(spawnEntityEvent);
                            if (spawnEntityEvent.isCancelled() || spawned.isRemoved()) {
                                for (Entity passenger : spawned.getIndirectPassengers()) {
                                    passenger.discard();
                                }
                                spawned.discard();
                            }
                        }
                    }
                };

                if (com.moulberry.axiom.VersionHelper.isFolia()) {
                    org.bukkit.Bukkit.getRegionScheduler().run(this.plugin, bukkitWorld, targetX, targetZ, task -> doSpawn.accept(null));
                } else {
                    doSpawn.accept(null);
                }
            };

            if (entry.copyFrom != null) {
                org.bukkit.entity.Entity sourceBukkitEntity = Bukkit.getEntity(entry.copyFrom);
                if (sourceBukkitEntity != null && com.moulberry.axiom.VersionHelper.isFolia()) {
                    sourceBukkitEntity.getScheduler().run(this.plugin, task -> {
                        org.bukkit.craftbukkit.entity.CraftEntity craftSource = (org.bukkit.craftbukkit.entity.CraftEntity) sourceBukkitEntity;
                        Entity entityCopyFrom = craftSource.getHandle();
                        var valueOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entityCopyFrom.registryAccess());
                        CompoundTag saved = entityCopyFrom.saveAsPassenger(valueOutput) ? valueOutput.buildResult() : null;
                        CompoundTag tag = entry.tag == null ? new CompoundTag() : entry.tag.copy();
                        if (saved != null) {
                            saved.remove("Dimension");
                            tag = tag.merge(saved);
                        }
                        spawnTask.accept(tag);
                    }, null);
                } else if (sourceBukkitEntity != null) {
                    org.bukkit.craftbukkit.entity.CraftEntity craftSource = (org.bukkit.craftbukkit.entity.CraftEntity) sourceBukkitEntity;
                    Entity entityCopyFrom = craftSource.getHandle();
                    var valueOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entityCopyFrom.registryAccess());
                    CompoundTag saved = entityCopyFrom.saveAsPassenger(valueOutput) ? valueOutput.buildResult() : null;
                    CompoundTag tag = entry.tag == null ? new CompoundTag() : entry.tag.copy();
                    if (saved != null) {
                        saved.remove("Dimension");
                        tag = tag.merge(saved);
                    }
                    spawnTask.accept(tag);
                } else {
                    spawnTask.accept(entry.tag);
                }
            } else {
                spawnTask.accept(entry.tag);
            }
        }
    }

}
