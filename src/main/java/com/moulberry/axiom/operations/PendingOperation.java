package com.moulberry.axiom.operations;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public interface PendingOperation {

    boolean isFinished();
    void tick(ServerLevel level);
    ServerPlayer executor();

    default void startFolia(net.minecraft.server.level.ServerLevel level, Runnable onComplete) {
        onComplete.run();
    }
}
