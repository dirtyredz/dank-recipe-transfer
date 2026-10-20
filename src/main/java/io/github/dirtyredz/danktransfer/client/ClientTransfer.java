package io.github.dirtyredz.danktransfer.client;

import io.github.dirtyredz.danktransfer.crafting.CraftingMenuAccess;
import io.github.dirtyredz.danktransfer.crafting.ItemKey;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.PatternSlot;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.Stack;
import io.github.dirtyredz.danktransfer.crafting.RecipePattern;
import io.github.dirtyredz.danktransfer.crafting.TransferOutcome;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan;
import io.github.dirtyredz.danktransfer.crafting.TransferPlanner;
import io.github.dirtyredz.danktransfer.dank.DankSource;
import io.github.dirtyredz.danktransfer.network.TransferChannel;
import io.github.dirtyredz.danktransfer.network.TransferMessages;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Entry;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Snapshot;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Subscribe;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferRequest;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Client side of the transfer, shared by every viewer adapter: preflight against the snapshot,
 * request sending, acknowledgement handling and subscription upkeep. Viewer adapters only
 * translate to and from their own APIs.
 */
public final class ClientTransfer {
    /** Ticks to wait for an acknowledgement before failing the pending transfer. */
    private static final int TIMEOUT_TICKS = 100;

    private static final DankSnapshotCache<ItemStack> CACHE = new DankSnapshotCache<>();
    private static final PendingTransfer PENDING = new PendingTransfer();

    private static long ticks;
    private static int subscribedMenu = DankSnapshotCache.NONE;
    @Nullable
    private static Snapshot<ItemStack> convertedFrom;
    private static List<Stack<ItemKey>> convertedStacks = List.of();

    private ClientTransfer() {
    }

    /** Whether the adapter can use the Dank path at all for this menu and recipe. */
    public enum State {
        /** Not a vanilla crafting grid, not a supported recipe, or no companion on the server. */
        UNSUPPORTED,
        /** Supported, but the first snapshot has not arrived: keep native behaviour. */
        NOT_SYNCED,
        /** Supported and planned against the snapshot. */
        READY
    }

    /** Preflight result. {@code plan} and {@code pattern} are null unless {@code state == READY}. */
    public record Preflight(State state, @Nullable List<PatternSlot<ItemKey>> pattern,
                            @Nullable TransferPlan<ItemKey> plan) {
        static final Preflight UNSUPPORTED = new Preflight(State.UNSUPPORTED, null, null);
        static final Preflight NOT_SYNCED = new Preflight(State.NOT_SYNCED, null, null);

        public boolean ready() {
            return state == State.READY;
        }

        /** Ready, complete, and actually needs Dank items (otherwise native transfer suffices). */
        public boolean needsDank() {
            return ready() && plan.ok() && plan.usesDank();
        }

        /** Ingredient indices (recipe ingredient list) of every missing pattern slot. */
        public List<Integer> missingIngredientIndices() {
            if (!ready()) {
                return List.of();
            }
            List<Integer> indices = new ArrayList<>(plan.missing().size());
            for (int patternIndex : plan.missing()) {
                indices.add(pattern.get(patternIndex).ingredientIndex());
            }
            return indices;
        }
    }

    /** Plans {@code batches} sets of {@code recipe} in {@code menu}. Never sends anything. */
    public static Preflight preflight(AbstractContainerMenu menu, @Nullable Object recipe, int batches) {
        Optional<CraftingMenuAccess> access = CraftingMenuAccess.of(menu);
        LocalPlayer player = Minecraft.getInstance().player;
        if (access.isEmpty() || !(recipe instanceof CraftingRecipe crafting) || player == null || !serverSupports()) {
            return Preflight.UNSUPPORTED;
        }
        Optional<List<PatternSlot<ItemKey>>> pattern = RecipePattern.of(crafting, access.get().width(), access.get().height());
        if (pattern.isEmpty()) {
            return Preflight.UNSUPPORTED;
        }
        Snapshot<ItemStack> snapshot = CACHE.current(menu.containerId);
        if (snapshot == null) {
            return Preflight.NOT_SYNCED;
        }
        List<List<Stack<ItemKey>>> danks = List.of(snapshotStacks(snapshot));
        TransferPlan<ItemKey> plan = TransferPlanner.plan(
                access.get().planInput(pattern.get(), danks, DankSource::isCarrier, player), batches);
        return new Preflight(State.READY, pattern.get(), plan);
    }

    /** The snapshot as one virtual Dank store. Converted once per snapshot. */
    private static List<Stack<ItemKey>> snapshotStacks(Snapshot<ItemStack> snapshot) {
        if (snapshot != convertedFrom) {
            List<Stack<ItemKey>> stacks = new ArrayList<>(snapshot.entries().size());
            for (Entry<ItemStack> entry : snapshot.entries()) {
                stacks.add(new Stack<>(ItemKey.of(entry.prototype()), (int) Math.min(entry.count(), Integer.MAX_VALUE)));
            }
            convertedStacks = List.copyOf(stacks);
            convertedFrom = snapshot;
        }
        return convertedStacks;
    }

    /** Stacks of the current snapshot for {@code menuId} (count-1 prototypes plus totals). */
    public static List<Entry<ItemStack>> snapshotEntries(int menuId) {
        Snapshot<ItemStack> snapshot = CACHE.current(menuId);
        return snapshot == null ? List.of() : snapshot.entries();
    }

    public static boolean busy() {
        return PENDING.busy();
    }

    /**
     * Sends one transfer request. {@code onDone} runs exactly once with the outcome.
     *
     * @return false when nothing was sent (busy, unsupported or not synced)
     */
    public static boolean send(AbstractContainerMenu menu, CraftingRecipe recipe, int batches, Consumer<TransferOutcome> onDone) {
        Snapshot<ItemStack> snapshot = CACHE.current(menu.containerId);
        if (snapshot == null || !serverSupports()) {
            return false;
        }
        OptionalInt id = PENDING.begin(ticks, outcome -> {
            if (!outcome.success()) {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(outcome.message(), true);
                }
            }
            onDone.accept(outcome);
        });
        if (id.isEmpty()) {
            return false;
        }
        int wire = batches == TransferPlanner.MAX ? TransferMessages.MAX_BATCHES : batches;
        TransferChannel.sendToServer(new TransferRequest(id.getAsInt(), menu.containerId, snapshot.session(),
                snapshot.revision(), recipe.getId(), wire));
        return true;
    }

    /** Whether the connected server runs the companion. */
    public static boolean serverSupports() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && TransferChannel.isRemotePresent(connection.getConnection());
    }

    /** Changes whenever a snapshot arrives or is dropped; viewers use it to refresh cached state. */
    public static int snapshotChanges() {
        return CACHE.changeCount();
    }

    public static long ticks() {
        return ticks;
    }

    // ---- network entry points (main thread) ----

    public static void onSnapshot(Snapshot<ItemStack> snapshot) {
        CACHE.accept(snapshot);
    }

    public static void onResult(TransferResult result) {
        PENDING.complete(result.requestId(), result.outcome());
    }

    // ---- lifecycle (driven by ClientLifecycle) ----

    /** Keeps the subscription in line with the open menu and times out lost acknowledgements. */
    static void tick() {
        ticks++;
        PENDING.expire(ticks, TIMEOUT_TICKS);
        Minecraft mc = Minecraft.getInstance();
        int desired = DankSnapshotCache.NONE;
        if (mc.player != null && mc.screen != null && CraftingMenuAccess.isSupported(mc.player.containerMenu)
                && serverSupports()) {
            desired = mc.player.containerMenu.containerId;
        }
        if (desired == subscribedMenu) {
            return;
        }
        PENDING.cancel(TransferOutcome.WRONG_MENU);
        if (subscribedMenu != DankSnapshotCache.NONE && serverSupports()) {
            TransferChannel.sendToServer(new Subscribe(subscribedMenu, false));
        }
        subscribedMenu = desired;
        CACHE.watch(desired);
        if (desired != DankSnapshotCache.NONE) {
            TransferChannel.sendToServer(new Subscribe(desired, true));
        }
    }

    /** Forgets all state; the next tick resubscribes if a supported menu is still open. */
    static void invalidate() {
        PENDING.cancel(TransferOutcome.WRONG_MENU);
        CACHE.invalidate();
        subscribedMenu = DankSnapshotCache.NONE;
        convertedFrom = null;
        convertedStacks = List.of();
    }
}
