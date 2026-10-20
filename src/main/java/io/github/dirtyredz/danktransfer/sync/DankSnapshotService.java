package io.github.dirtyredz.danktransfer.sync;

import io.github.dirtyredz.danktransfer.crafting.CraftingMenuAccess;
import io.github.dirtyredz.danktransfer.crafting.ItemKey;
import io.github.dirtyredz.danktransfer.dank.CarriedDank;
import io.github.dirtyredz.danktransfer.dank.DankSource;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Entry;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Snapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * Server-side Dank snapshot subscriptions. A client subscribes while a supported crafting menu is
 * open; the service checks for content changes at most every {@link #CHECK_INTERVAL} ticks and
 * sends only changes. A subscription expires when its menu closes or is replaced, or on logout.
 *
 * <p>Also remembers recent request ids per player so duplicates are rejected.
 */
public final class DankSnapshotService {
    public static final int CHECK_INTERVAL = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(DankSnapshotService.class);
    private static final int REMEMBERED_REQUESTS = 64;

    /** Live subscription state for one player. */
    public static final class Subscription {
        private final int menuId;
        private final int session;
        private int revision;
        private Map<ItemKey, Long> lastContents = Map.of();
        private boolean sentOnce;

        Subscription(int menuId, int session) {
            this.menuId = menuId;
            this.session = session;
        }

        public int menuId() {
            return menuId;
        }

        public int session() {
            return session;
        }

        public int revision() {
            return revision;
        }
    }

    private final Map<UUID, Subscription> subscriptions = new HashMap<>();
    private final Map<UUID, LinkedHashSet<Integer>> recentRequests = new HashMap<>();
    private final BiConsumer<ServerPlayer, Snapshot<ItemStack>> sender;
    private int tickCounter;

    public DankSnapshotService(BiConsumer<ServerPlayer, Snapshot<ItemStack>> sender) {
        this.sender = sender;
    }

    /**
     * Starts (or restarts) a subscription and sends the first snapshot immediately. A menu the
     * server cannot serve gets an explicit "unsupported" reply, so the client falls back to native
     * behaviour instead of waiting for a snapshot that will never come.
     */
    public void subscribe(ServerPlayer player, int menuId) {
        AbstractContainerMenu open = player.containerMenu;
        if (open.containerId != menuId || !CraftingMenuAccess.isSupported(open)) {
            subscriptions.remove(player.getUUID());
            LOGGER.debug("Refused Dank snapshot subscription from {} for menu {} (open: {} #{})",
                    player.getGameProfile().getName(), menuId, open.getClass().getName(), open.containerId);
            sender.accept(player, Snapshot.unsupported(menuId));
            return;
        }
        Subscription subscription = new Subscription(menuId, ThreadLocalRandom.current().nextInt());
        subscriptions.put(player.getUUID(), subscription);
        LOGGER.debug("Dank snapshot subscription from {} for menu {} ({})",
                player.getGameProfile().getName(), menuId, open.getClass().getName());
        refresh(player, subscription);
    }

    public void unsubscribe(ServerPlayer player, int menuId) {
        Subscription current = subscriptions.get(player.getUUID());
        if (current != null && current.menuId == menuId) {
            subscriptions.remove(player.getUUID());
        }
    }

    public void forget(ServerPlayer player) {
        subscriptions.remove(player.getUUID());
        recentRequests.remove(player.getUUID());
    }

    public Optional<Subscription> subscription(ServerPlayer player) {
        Subscription subscription = subscriptions.get(player.getUUID());
        if (subscription != null && !stillOpen(player, subscription)) {
            subscriptions.remove(player.getUUID());
            return Optional.empty();
        }
        return Optional.ofNullable(subscription);
    }

    /**
     * Records a request id.
     *
     * @return false when this id was already seen for this player
     */
    public boolean firstUse(ServerPlayer player, int requestId) {
        LinkedHashSet<Integer> seen = recentRequests.computeIfAbsent(player.getUUID(), id -> new LinkedHashSet<>());
        if (!seen.add(requestId)) {
            return false;
        }
        if (seen.size() > REMEMBERED_REQUESTS) {
            Iterator<Integer> oldest = seen.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /** Server tick: expire closed subscriptions and push changes every few ticks. */
    public void tick(MinecraftServer server) {
        if (++tickCounter % CHECK_INTERVAL != 0 || subscriptions.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Subscription>> it = subscriptions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Subscription> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !stillOpen(player, entry.getValue())) {
                it.remove();
                continue;
            }
            refresh(player, entry.getValue());
        }
    }

    /**
     * Rechecks contents now (used before and after a transfer) and sends a snapshot when they
     * changed or none was sent yet.
     */
    public void refresh(ServerPlayer player) {
        subscription(player).ifPresent(subscription -> refresh(player, subscription));
    }

    private void refresh(ServerPlayer player, Subscription subscription) {
        Map<ItemKey, Long> contents = aggregate(DankSource.discover(player));
        if (subscription.sentOnce && contents.equals(subscription.lastContents)) {
            return;
        }
        if (subscription.sentOnce) {
            subscription.revision++;
        }
        subscription.lastContents = contents;
        subscription.sentOnce = true;
        sender.accept(player, toSnapshot(subscription, contents));
    }

    private static boolean stillOpen(ServerPlayer player, Subscription subscription) {
        return player.containerMenu.containerId == subscription.menuId
                && CraftingMenuAccess.isSupported(player.containerMenu);
    }

    /** Sums identical items across every slot of every Dank, in wide arithmetic. */
    public static Map<ItemKey, Long> aggregate(Collection<CarriedDank> danks) {
        Map<ItemKey, Long> totals = new LinkedHashMap<>();
        for (CarriedDank dank : danks) {
            for (ItemStack stack : dank.copyContents()) {
                if (!stack.isEmpty()) {
                    totals.merge(ItemKey.of(stack), (long) stack.getCount(), Long::sum);
                }
            }
        }
        return totals;
    }

    private static Snapshot<ItemStack> toSnapshot(Subscription subscription, Map<ItemKey, Long> contents) {
        List<Entry<ItemStack>> entries = new ArrayList<>(contents.size());
        for (Map.Entry<ItemKey, Long> e : contents.entrySet()) {
            entries.add(new Entry<>(e.getKey().toStack(1), e.getValue()));
        }
        return new Snapshot<>(subscription.menuId, subscription.session, subscription.revision, true, false, entries);
    }
}
