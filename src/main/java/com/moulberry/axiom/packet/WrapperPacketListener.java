package com.moulberry.axiom.packet;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

public class WrapperPacketListener implements PluginMessageListener {

    private final PacketHandler<?> packetHandler;

    public WrapperPacketListener(PacketHandler<?> packetHandler) {
        this.packetHandler = packetHandler;
    }

    @SuppressWarnings("unchecked")
    private static <T> void dispatch(PacketHandler<T> handler, Player player, Object parsed) {
        handler.apply(player, (T) parsed);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String s, @NotNull Player player, @NotNull byte[] bytes) {
        byte[] copiedBytes = bytes.clone();
        if (VersionHelper.isFolia() && packetHandler.handleAsync()) {
            java.util.UUID uuid = player.getUniqueId();
            int protocolVersion = AxiomPaper.PLUGIN.getProtocolVersionFor(uuid);
            var registryAccess = ((CraftPlayer) player).getHandle().registryAccess();

            player.getScheduler().run(AxiomPaper.PLUGIN, task -> {
                if (!player.isOnline()) return;
                RegistryFriendlyByteBuf friendlyByteBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(copiedBytes), registryAccess);
                if (!packetHandler.precheck(player, AxiomPaper.PLUGIN, friendlyByteBuf)) {
                    return;
                }
                friendlyByteBuf.readerIndex(0); // Reset reader index for parse

                java.util.concurrent.CompletableFuture<Void> oldChain = AxiomPaper.PLUGIN.playerPacketChainMap.get(uuid);
                java.util.concurrent.CompletableFuture<Void> nextChain = new java.util.concurrent.CompletableFuture<>();
                if (oldChain == null) {
                    AxiomPaper.PLUGIN.playerPacketChainMap.put(uuid, nextChain);
                    oldChain = java.util.concurrent.CompletableFuture.completedFuture(null);
                } else {
                    AxiomPaper.PLUGIN.playerPacketChainMap.put(uuid, nextChain);
                }

                final java.util.concurrent.CompletableFuture<Void> finalOldChain = oldChain;
                try {
                    finalOldChain.thenRunAsync(() -> {
                        try {
                            Object parsed = packetHandler.parse(uuid, protocolVersion, friendlyByteBuf);

                            if (player.getScheduler().run(AxiomPaper.PLUGIN, task2 -> {
                                try {
                                    if (player.isOnline()) {
                                        try {
                                            dispatch(packetHandler, player, parsed);
                                        } catch (Throwable t) {
                                            player.kick(Component.text("Error while processing packet " + s + ": " + t.getMessage()));
                                        }
                                    }
                                } finally {
                                    nextChain.complete(null);
                                }
                            }, () -> nextChain.complete(null)) == null) {
                                // Retired: scheduler rejected task, complete chain immediately
                                nextChain.complete(null);
                            }
                        } catch (Throwable t) {
                            try {
                                if (player.getScheduler().run(AxiomPaper.PLUGIN, task2 -> {
                                    if (player.isOnline()) {
                                        player.kick(Component.text("Error while parsing packet " + s + ": " + t.getMessage()));
                                    }
                                }, () -> {}) == null) {
                                    // Retired
                                }
                            } finally {
                                nextChain.complete(null);
                            }
                        }
                    }, AxiomPaper.PLUGIN.getAsyncExecutor()).exceptionally(ex -> {
                        nextChain.complete(null);
                        return null;
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    try {
                        player.getScheduler().run(AxiomPaper.PLUGIN, task2 -> {
                            if (player.isOnline()) {
                                player.kick(Component.text("Server busy, failed to parse packet " + s));
                            }
                        }, null);
                    } finally {
                        nextChain.complete(null);
                    }
                }
            }, null);
        } else if (!VersionHelper.isFolia() && packetHandler.handleAsync()) {
            java.util.UUID uuid = player.getUniqueId();
            int protocolVersion = AxiomPaper.PLUGIN.getProtocolVersionFor(uuid);
            var registryAccess = ((CraftPlayer) player).getHandle().registryAccess();

            RegistryFriendlyByteBuf friendlyByteBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(copiedBytes), registryAccess);
            if (!packetHandler.precheck(player, AxiomPaper.PLUGIN, friendlyByteBuf)) {
                return;
            }
            friendlyByteBuf.readerIndex(0); // Reset for parse

            java.util.concurrent.CompletableFuture<Void> oldChain = AxiomPaper.PLUGIN.playerPacketChainMap.get(uuid);
            java.util.concurrent.CompletableFuture<Void> nextChain = new java.util.concurrent.CompletableFuture<>();
            if (oldChain == null) {
                AxiomPaper.PLUGIN.playerPacketChainMap.put(uuid, nextChain);
                oldChain = java.util.concurrent.CompletableFuture.completedFuture(null);
            } else {
                AxiomPaper.PLUGIN.playerPacketChainMap.put(uuid, nextChain);
            }

            final java.util.concurrent.CompletableFuture<Void> finalOldChain = oldChain;
            try {
                finalOldChain.thenRunAsync(() -> {
                    try {
                        Object parsed = packetHandler.parse(uuid, protocolVersion, friendlyByteBuf);

                        org.bukkit.Bukkit.getScheduler().runTask(AxiomPaper.PLUGIN, () -> {
                            try {
                                if (player.isOnline()) {
                                    try {
                                        dispatch(packetHandler, player, parsed);
                                    } catch (Throwable t) {
                                        player.kick(Component.text("Error while processing packet " + s + ": " + t.getMessage()));
                                    }
                                }
                            } finally {
                                nextChain.complete(null);
                            }
                        });
                    } catch (Throwable t) {
                        try {
                            org.bukkit.Bukkit.getScheduler().runTask(AxiomPaper.PLUGIN, () -> {
                                if (player.isOnline()) {
                                    player.kick(Component.text("Error while parsing packet " + s + ": " + t.getMessage()));
                                }
                            });
                        } finally {
                            nextChain.complete(null);
                        }
                    }
                }, AxiomPaper.PLUGIN.getAsyncExecutor()).exceptionally(ex -> {
                    nextChain.complete(null);
                    return null;
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                try {
                    player.kick(Component.text("Server busy, failed to parse packet " + s));
                } finally {
                    nextChain.complete(null);
                }
            }
        } else if (VersionHelper.isFolia()) {
            player.getScheduler().run(AxiomPaper.PLUGIN, task -> {
                if (player.isOnline()) {
                    RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(copiedBytes), ((CraftPlayer) player).getHandle().registryAccess());
                    try {
                        packetHandler.onReceive(player, buf);
                    } catch (Throwable t) {
                        player.kick(Component.text("Error while processing packet " + s + ": " + t.getMessage()));
                    }
                }
            }, null);
        } else {
            RegistryFriendlyByteBuf friendlyByteBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(copiedBytes), ((CraftPlayer) player).getHandle().registryAccess());
            try {
                this.packetHandler.onReceive(player, friendlyByteBuf);
            } catch (Throwable t) {
                player.kick(Component.text("Error while processing packet " + s + ": " + t.getMessage()));
            }
        }
    }
}
