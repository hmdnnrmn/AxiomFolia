package com.moulberry.axiom.packet;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.papermc.paper.connection.DisconnectionReason;
import io.papermc.paper.network.ConnectionEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.Utf8String;
import net.minecraft.network.VarInt;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public class AxiomBigPayloadHandler extends MessageToMessageDecoder<ByteBuf> {

    private final int payloadId;
    private final Connection connection;
    private final Map<String, PacketHandler<?>> packetHandlers;
    private boolean handleUserEvents;

    public AxiomBigPayloadHandler(int payloadId, Connection connection, Map<String, PacketHandler<?>> packetHandlers, boolean handleUserEvents) {
        this.payloadId = payloadId;
        this.connection = connection;
        this.packetHandlers = packetHandlers;
        this.handleUserEvents = handleUserEvents;
    }

    @SuppressWarnings("unchecked")
    private static <T> void dispatch(PacketHandler<T> handler, Player player, Object parsed) {
        handler.apply(player, (T) parsed);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // Skip if no readable bytes or inactive channel
        if (in.readableBytes() == 0 || !ctx.channel().isActive()) {
            out.add(in.retain());
            return;
        }

        // Don't handle if player doesn't have permission to use Axiom
        ServerPlayer player = this.connection.getPlayer();
        if (player == null || player.hasDisconnected() || !AxiomPaper.PLUGIN.canUseAxiom(player.getBukkitEntity())) {
            out.add(in.retain());
            return;
        }

        int readerIndex = in.readerIndex();
        boolean success = false;
        boolean allowIndexOutOfBounds = true;
        try {
            int packetId = VarInt.read(in);

            if (packetId == this.payloadId) {
                String identifier = Utf8String.read(in, 32767);
                allowIndexOutOfBounds = false;

                PacketHandler<?> handler = this.packetHandlers.get(identifier);
                if (handler != null) {
                    RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(in, player.registryAccess());
                    byte[] bytes = ByteBufUtil.getBytes(buf);

                    if (VersionHelper.isFolia()) {
                        java.util.UUID uuid = player.getUUID();
                        int protocolVersion = AxiomPaper.PLUGIN.getProtocolVersionFor(uuid);
                        var registryAccess = player.registryAccess();
                        var bukkitPlayer = player.getBukkitEntity();

                        if (handler.handleAsync()) {
                            bukkitPlayer.getScheduler().run(AxiomPaper.PLUGIN, task -> {
                                if (player.hasDisconnected()) return;
                                RegistryFriendlyByteBuf friendlyByteBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registryAccess);
                                if (!handler.precheck(bukkitPlayer, AxiomPaper.PLUGIN, friendlyByteBuf)) {
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
                                            Object parsed = handler.parse(uuid, protocolVersion, friendlyByteBuf);

                                            if (bukkitPlayer.getScheduler().run(AxiomPaper.PLUGIN, task2 -> {
                                                try {
                                                    if (!player.hasDisconnected()) {
                                                        try {
                                                            dispatch(handler, bukkitPlayer, parsed);
                                                        } catch (Throwable t) {
                                                            player.connection.disconnectAsync(net.minecraft.network.chat.Component.literal("Error while processing Axiom packet " + identifier + ": " + t.getMessage()), DisconnectionReason.UNKNOWN);
                                                        }
                                                    }
                                                } finally {
                                                    nextChain.complete(null);
                                                }
                                            }, () -> nextChain.complete(null)) == null) {
                                                nextChain.complete(null);
                                            }
                                        } catch (Throwable t) {
                                            try {
                                                if (bukkitPlayer.getScheduler().run(AxiomPaper.PLUGIN, task2 -> {
                                                    if (!player.hasDisconnected()) {
                                                        player.connection.disconnectAsync(net.minecraft.network.chat.Component.literal("Error while parsing Axiom packet " + identifier + ": " + t.getMessage()), DisconnectionReason.UNKNOWN);
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
                                        if (bukkitPlayer.getScheduler().run(AxiomPaper.PLUGIN, task2 -> {
                                            if (!player.hasDisconnected()) {
                                                player.connection.disconnectAsync(net.minecraft.network.chat.Component.literal("Server busy, failed to parse Axiom packet " + identifier), DisconnectionReason.UNKNOWN);
                                            }
                                        }, () -> {}) == null) {
                                            // Retired
                                        }
                                    } finally {
                                        nextChain.complete(null);
                                    }
                                }
                            }, null);
                        } else {
                            bukkitPlayer.getScheduler().run(AxiomPaper.PLUGIN, task -> {
                                if (!player.hasDisconnected()) {
                                    RegistryFriendlyByteBuf parseBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registryAccess);
                                    try {
                                        handler.onReceive(bukkitPlayer, parseBuf);
                                    } catch (Throwable t) {
                                        player.connection.disconnectAsync(net.minecraft.network.chat.Component.literal("Error while processing Axiom packet " + identifier + ": " + t.getMessage()), DisconnectionReason.UNKNOWN);
                                    }
                                }
                            }, null);
                        }
                    } else {
                        // Non-Folia path
                        if (handler.handleAsync()) {
                            java.util.UUID uuid = player.getUUID();
                            int protocolVersion = AxiomPaper.PLUGIN.getProtocolVersionFor(uuid);
                            var registryAccess = player.registryAccess();
                            var bukkitPlayer = player.getBukkitEntity();

                            player.level().getServer().execute(() -> {
                                if (player.hasDisconnected()) return;
                                RegistryFriendlyByteBuf friendlyByteBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registryAccess);
                                if (!handler.precheck(bukkitPlayer, AxiomPaper.PLUGIN, friendlyByteBuf)) {
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
                                            Object parsed = handler.parse(uuid, protocolVersion, friendlyByteBuf);

                                            player.level().getServer().execute(() -> {
                                                try {
                                                    if (!player.hasDisconnected()) {
                                                        try {
                                                            dispatch(handler, bukkitPlayer, parsed);
                                                        } catch (Throwable t) {
                                                            player.connection.disconnectAsync(net.minecraft.network.chat.Component.literal("Error while processing Axiom packet " + identifier + ": " + t.getMessage()), DisconnectionReason.UNKNOWN);
                                                        }
                                                    }
                                                } finally {
                                                    nextChain.complete(null);
                                                }
                                            });
                                        } catch (Throwable t) {
                                            try {
                                                player.level().getServer().execute(() -> {
                                                    if (!player.hasDisconnected()) {
                                                        player.connection.disconnectAsync(net.minecraft.network.chat.Component.literal("Error while parsing Axiom packet " + identifier + ": " + t.getMessage()), DisconnectionReason.UNKNOWN);
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
                                        player.level().getServer().execute(() -> {
                                            if (!player.hasDisconnected()) {
                                                player.connection.disconnectAsync(net.minecraft.network.chat.Component.literal("Server busy, failed to parse Axiom packet " + identifier), DisconnectionReason.UNKNOWN);
                                            }
                                        });
                                    } finally {
                                        nextChain.complete(null);
                                    }
                                }
                            });
                        } else {
                            player.level().getServer().execute(() -> {
                                RegistryFriendlyByteBuf parseBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), player.registryAccess());
                                callReceive(handler, player, parseBuf, identifier);
                            });
                        }
                    }

                    success = true;
                    in.readerIndex(in.writerIndex());
                    return;
                }
            }
        } catch (Throwable t) {
            if (!(t instanceof IndexOutOfBoundsException && allowIndexOutOfBounds)) {
                // Skip remaining bytes
                success = true;
                in.skipBytes(in.readableBytes());

                // Throw error, will disconnect client
                throw t;
            }
        } finally {
            if (!success) {
                in.readerIndex(readerIndex);
            }
        }

        out.add(in.retain());
    }

    private static void callReceive(PacketHandler<?> handler, ServerPlayer player, RegistryFriendlyByteBuf friendlyByteBuf, String identifier) {
        if (player.hasDisconnected()) {
            return;
        }
        try {
            handler.onReceive(player.getBukkitEntity(), friendlyByteBuf);
        } catch (Throwable t) {
            player.connection.disconnectAsync(net.minecraft.network.chat.Component.literal("Error while processing Axiom packet " + identifier + ": " + t.getMessage()), DisconnectionReason.UNKNOWN);
        }
    }

    public static void apply(ChannelPipeline pipeline, AxiomBigPayloadHandler handler) {
        if (pipeline.get("axiom-big-payload-handler") != null) {
            pipeline.remove("axiom-big-payload-handler");
        }
        pipeline.addBefore("decoder", "axiom-big-payload-handler", handler);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (this.handleUserEvents) {
            if (evt == ConnectionEvent.COMPRESSION_THRESHOLD_SET || evt == ConnectionEvent.COMPRESSION_DISABLED) {
                AxiomBigPayloadHandler handler = new AxiomBigPayloadHandler(this.payloadId, this.connection, this.packetHandlers, false);
                apply(ctx.pipeline(), handler);
                super.userEventTriggered(ctx, evt);
                handler.handleUserEvents = true;
                return;
            }
        }

        super.userEventTriggered(ctx, evt);
    }

}
