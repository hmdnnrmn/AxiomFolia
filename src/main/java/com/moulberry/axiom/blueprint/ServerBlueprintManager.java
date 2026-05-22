package com.moulberry.axiom.blueprint;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.restrictions.AxiomPermission;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ServerBlueprintManager {

    private static ServerBlueprintRegistry registry = null;

    public static void initialize(Path blueprintDirectory) {
        Map<String, RawBlueprint> map = new HashMap<>();
        loadRegistryFromFolder(map, blueprintDirectory, "/");
        registry = new ServerBlueprintRegistry(map);
    }

    private static final int MAX_SIZE = 1000000;
    private static final Identifier PACKET_BLUEPRINT_MANIFEST_IDENTIFIER = VersionHelper.createIdentifier("axiom:blueprint_manifest");

    public static void sendManifest(List<ServerPlayer> serverPlayers) {
        synchronized (ServerBlueprintManager.class) {
            if (registry == null) return;

            List<byte[]> payloads = new ArrayList<>();
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeBoolean(true); // replace

            for (Map.Entry<String, RawBlueprint> entry : registry.blueprints().entrySet()) {
                buf.writeUtf(entry.getKey());
                RawBlueprint.writeHeader(buf, entry.getValue());

                if (buf.writerIndex() > MAX_SIZE) {
                    buf.writeUtf("");
                    payloads.add(ByteBufUtil.getBytes(buf));
                    buf.clear();
                    buf.writeBoolean(false); // don't replace
                }
            }

            buf.writeUtf("");
            payloads.add(ByteBufUtil.getBytes(buf));

            for (ServerPlayer serverPlayer : serverPlayers) {
                org.bukkit.entity.Player bukkitPlayer = serverPlayer.getBukkitEntity();
                if (VersionHelper.isFolia()) {
                    bukkitPlayer.getScheduler().run(AxiomPaper.PLUGIN, task -> {
                        if (bukkitPlayer.isOnline() && AxiomPaper.PLUGIN.canUseAxiom(bukkitPlayer, AxiomPermission.BLUEPRINT_MANIFEST)) {
                            for (byte[] payload : payloads) {
                                var customPayload = VersionHelper.createCustomPayload(PACKET_BLUEPRINT_MANIFEST_IDENTIFIER, payload);
                                serverPlayer.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(customPayload));
                            }
                        }
                    }, null);
                } else {
                    if (bukkitPlayer.isOnline() && AxiomPaper.PLUGIN.canUseAxiom(bukkitPlayer, AxiomPermission.BLUEPRINT_MANIFEST)) {
                        for (byte[] payload : payloads) {
                            var customPayload = VersionHelper.createCustomPayload(PACKET_BLUEPRINT_MANIFEST_IDENTIFIER, payload);
                            serverPlayer.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(customPayload));
                        }
                    }
                }
            }
        }
    }

    public static ServerBlueprintRegistry getRegistry() {
        return registry;
    }

    private static void loadRegistryFromFolder(Map<String, RawBlueprint> map, Path folder, String location) {
        if (!Files.isDirectory(folder)) {
            return;
        }

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(folder)) {
            for (Path path : directoryStream) {
                String filename = path.getFileName().toString();
                if (filename.endsWith(".bp")) {
                    try {
                        RawBlueprint rawBlueprint = BlueprintIo.readRawBlueprint(new BufferedInputStream(Files.newInputStream(path)));
                        String newLocation = location + filename.substring(0, filename.length()-3);
                        map.put(newLocation, rawBlueprint);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else if (Files.isDirectory(path)) {
                    String newLocation = location + filename + "/";
                    loadRegistryFromFolder(map, path, newLocation);
                }
            }
        } catch (IOException ignored) {}
    }

}
