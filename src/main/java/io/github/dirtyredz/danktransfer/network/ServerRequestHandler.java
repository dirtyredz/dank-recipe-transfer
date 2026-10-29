package io.github.dirtyredz.danktransfer.network;

import io.github.dirtyredz.danktransfer.crafting.CraftingMenuAccess;
import io.github.dirtyredz.danktransfer.crafting.TransferExecutor;
import io.github.dirtyredz.danktransfer.crafting.TransferOutcome;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Subscribe;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferRequest;
import io.github.dirtyredz.danktransfer.sync.DankSnapshotService;
import io.github.dirtyredz.danktransfer.sync.DankSnapshotService.Subscription;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Optional;

/**
 * Server-side protocol rules, run on the server thread: subscription bookkeeping and full
 * validation of transfer requests before the executor touches anything.
 */
public final class ServerRequestHandler {
    private final DankSnapshotService snapshots;
    private final TransferExecutor executor;

    public ServerRequestHandler(DankSnapshotService snapshots, TransferExecutor executor) {
        this.snapshots = snapshots;
        this.executor = executor;
    }

    public DankSnapshotService snapshots() {
        return snapshots;
    }

    public void onSubscribe(ServerPlayer player, Subscribe message) {
        if (message.active()) {
            snapshots.subscribe(player, message.menuId());
        } else {
            snapshots.unsubscribe(player, message.menuId());
        }
    }

    /** Validates and executes one request. Always refreshes the snapshot afterwards. */
    public TransferOutcome onTransfer(ServerPlayer player, TransferRequest request) {
        if (!snapshots.firstUse(player, request.requestId())) {
            return TransferOutcome.DUPLICATE;
        }
        TransferOutcome outcome = validateAndRun(player, request);
        snapshots.refresh(player);
        return outcome;
    }

    private TransferOutcome validateAndRun(ServerPlayer player, TransferRequest request) {
        if (!request.legalQuantity()) {
            return TransferOutcome.ILLEGAL_QUANTITY;
        }
        if (player.containerMenu.containerId != request.menuId()) {
            return TransferOutcome.WRONG_MENU;
        }
        Optional<CraftingMenuAccess> access = CraftingMenuAccess.of(player.containerMenu);
        if (access.isEmpty()) {
            return TransferOutcome.WRONG_MENU;
        }
        Optional<Subscription> subscription = snapshots.subscription(player);
        if (subscription.isEmpty() || subscription.get().session() != request.session()) {
            return TransferOutcome.STALE;
        }
        // Pick up any change since the last periodic check; a moved revision means the client
        // planned against old contents. The refreshed snapshot is already on its way.
        snapshots.refresh(player);
        if (subscription.get().revision() != request.revision()) {
            return TransferOutcome.STALE;
        }
        // The holder keeps the requested id through execution (Polymorph selection included).
        Optional<RecipeHolder<?>> recipe = player.server.getRecipeManager().byKey(request.recipeId());
        if (recipe.isEmpty() || !(recipe.get().value() instanceof CraftingRecipe crafting)
                || crafting.getType() != RecipeType.CRAFTING) {
            return TransferOutcome.INVALID_RECIPE;
        }
        return executor.execute(player, access.get(), new RecipeHolder<>(recipe.get().id(), crafting), request.batches());
    }
}
