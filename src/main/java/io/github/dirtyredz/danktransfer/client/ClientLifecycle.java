package io.github.dirtyredz.danktransfer.client;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;

/** Client events that drive {@link ClientTransfer}'s subscription and invalidation. */
@EventBusSubscriber(modid = DankRecipeTransfer.MOD_ID, value = Dist.CLIENT)
public final class ClientLifecycle {
    private ClientLifecycle() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientTransfer.tick();
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
