package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.blueprint.BlueprintIo;
import com.moulberry.axiom.blueprint.RawBlueprint;
import com.moulberry.axiom.blueprint.ServerBlueprintManager;
import com.moulberry.axiom.blueprint.ServerBlueprintRegistry;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class UploadBlueprintPacketListener implements PacketHandler<UploadBlueprintPacketListener.Parsed> {

    private final AxiomPaper plugin;
    public UploadBlueprintPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    public record Parsed(String pathName, RawBlueprint rawBlueprint, Path relative) {}

    @Override
    public boolean handleAsync() {
        return true;
    }

    @Override
    public boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.canUseAxiom(player, AxiomPermission.BLUEPRINT_UPLOAD)) {
            return false;
        }

        if (this.plugin.isMismatchedDataVersion(player.getUniqueId())) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Axiom+ViaVersion: This feature isn't supported. Switch your client version to " + VersionHelper.getVersion() + " to use this"));
            return false;
        }

        if (ServerBlueprintManager.getRegistry() == null || this.plugin.blueprintFolder == null) {
            return false;
        }

        return true;
    }

    @Override
    public Parsed parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf) {
        String pathStr = friendlyByteBuf.readUtf();
        RawBlueprint rawBlueprint = RawBlueprint.read(friendlyByteBuf);

        pathStr = pathStr.replace("\\", "/");

        if (!pathStr.endsWith(".bp") || pathStr.contains("..") || !pathStr.startsWith("/")) {
            return null;
        }

        pathStr = pathStr.substring(1);

        Path relative = Path.of(pathStr).normalize();
        if (relative.isAbsolute()) {
            return null;
        }

        String pathName = pathStr.substring(0, pathStr.length()-3);
        return new Parsed(pathName, rawBlueprint, relative);
    }

    @Override
    public void apply(Player player, Parsed parsed) {
        if (parsed == null) return;

        ServerBlueprintRegistry registry = ServerBlueprintManager.getRegistry();
        if (registry == null || this.plugin.blueprintFolder == null) {
            return;
        }

        try {
            Path path = this.plugin.blueprintFolder.resolve(parsed.relative());

            // Write file
            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                return;
            }
            try (OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(path))) {
                BlueprintIo.writeRaw(outputStream, parsed.rawBlueprint());
            } catch (IOException e) {
                return;
            }

            // Update registry under lock
            synchronized (ServerBlueprintManager.class) {
                registry.blueprints().put("/" + parsed.pathName(), parsed.rawBlueprint());
            }

            // Resend manifest
            ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();
            ServerBlueprintManager.sendManifest(serverPlayer.level().getServer().getPlayerList().getPlayers());
        } catch (Throwable t) {
            player.kick(net.kyori.adventure.text.Component.text(
                    "An error occured while uploading blueprint: " + t.getMessage()));
        }
    }

}
