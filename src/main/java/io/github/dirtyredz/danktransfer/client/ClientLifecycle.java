package io.github.dirtyredz.danktransfer.client;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client events that drive {@link ClientTransfer}'s subscription and invalidation. */
@Mod.EventBusSubscriber(modid = DankRecipeTransfer.MOD_ID, value = Dist.CLIENT)
public final class ClientLifecycle {
    private ClientLifecycle() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ClientTransfer.tick();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientTransfer.invalidate();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientTransfer.invalidate();
    }

    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        // Respawn and dimension change replace the player and its menus.
        ClientTransfer.invalidate();
    }

    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        ClientTransfer.invalidate();
    }
}
