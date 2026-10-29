package io.github.dirtyredz.danktransfer;

import io.github.dirtyredz.danktransfer.crafting.TransferExecutor;
import io.github.dirtyredz.danktransfer.network.ServerRequestHandler;
import io.github.dirtyredz.danktransfer.network.TransferChannel;
import io.github.dirtyredz.danktransfer.sync.DankSnapshotService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Bootstrap. Loads on both sides with neither JEI nor EMI present; viewer integration is applied
 * only on the client by guarded mixins ({@code compat.MixinGate}).
 */
@Mod(DankRecipeTransfer.MOD_ID)
public final class DankRecipeTransfer {
    public static final String MOD_ID = "dank_recipe_transfer";

    private static ServerRequestHandler server;

    public DankRecipeTransfer(IEventBus modBus) {
        DankSnapshotService snapshots = new DankSnapshotService(TransferChannel::sendSnapshot);
        server = new ServerRequestHandler(snapshots, TransferExecutor.standard());
        TransferChannel.register(modBus, server);

        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> snapshots.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                snapshots.forget(player);
            }
        });
    }

    /** The live server-side protocol handler (GameTests drive it directly). */
    public static ServerRequestHandler server() {
        return server;
    }
}
