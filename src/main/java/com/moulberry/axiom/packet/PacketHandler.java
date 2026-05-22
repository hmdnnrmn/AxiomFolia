package com.moulberry.axiom.packet;

import com.moulberry.axiom.AxiomPaper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.bukkit.entity.Player;

import java.util.UUID;

public interface PacketHandler<T> {

    default boolean handleAsync() {
        return false;
    }

    default boolean precheck(Player player, AxiomPaper plugin, RegistryFriendlyByteBuf friendlyByteBuf) {
        return true;
    }

    T parse(UUID playerUuid, int protocolVersion, RegistryFriendlyByteBuf friendlyByteBuf);

    void apply(Player player, T parsed);

    default void onReceive(Player player, RegistryFriendlyByteBuf friendlyByteBuf) {
        if (!precheck(player, AxiomPaper.PLUGIN, friendlyByteBuf)) {
            return;
        }
        T parsed = parse(player.getUniqueId(), AxiomPaper.PLUGIN.getProtocolVersionFor(player.getUniqueId()), friendlyByteBuf);
        apply(player, parsed);
    }

}
