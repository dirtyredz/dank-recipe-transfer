package io.github.dirtyredz.danktransfer;

import io.github.dirtyredz.danktransfer.crafting.TransferExecutor;
import io.github.dirtyredz.danktransfer.network.ServerRequestHandler;
import io.github.dirtyredz.danktransfer.network.TransferChannel;
import io.github.dirtyredz.danktransfer.sync.DankSnapshotService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;

/**
 * Bootstrap. Loads on both sides with neither JEI nor EMI present; viewer integration is applied
 * only on the client by guarded mixins ({@code compat.MixinGate}).
 */
@Mod(DankRecipeTransfer.MOD_ID)
public final class DankRecipeTransfer {
    public static final String MOD_ID = "dank_recipe_transfer";

    private static ServerRequestHandler server;

    public DankRecipeTransfer() {
        DankSnapshotService snapshots = new DankSnapshotService(TransferChannel::sendTo);
        server = new ServerRequestHandler(snapshots, TransferExecutor.standard());
        TransferChannel.register(server);

        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class, event -> {
            if (event.phase == TickEvent.Phase.END) {
                snapshots.tick(event.getServer());
            }
        });
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent.class, event -> {
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
