package com.moulberry.axiom.viaversion;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

public class UnknownVersionHelper {

    public static CompoundTag readTagUnknown(FriendlyByteBuf friendlyByteBuf, int playerProtocolVersion) {
        if (playerProtocolVersion != SharedConstants.getProtocolVersion()) {
            return ViaVersionHelper.readTagViaVersion(friendlyByteBuf, playerProtocolVersion);
        } else {
            return friendlyByteBuf.readNbt();
        }
    }

    public static void readPalettedContainerUnknown(FriendlyByteBuf friendlyByteBuf, PalettedContainer<BlockState> palettedContainer, int playerProtocolVersion) {
        if (playerProtocolVersion != SharedConstants.getProtocolVersion()) {
            ViaVersionHelper.readPalettedContainerViaVersion(friendlyByteBuf, palettedContainer, playerProtocolVersion);
        } else {
            palettedContainer.read(friendlyByteBuf);
        }
    }

}
