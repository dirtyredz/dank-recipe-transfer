package io.github.dirtyredz.danktransfer.client;

import io.github.dirtyredz.danktransfer.network.TransferMessages.Snapshot;
import org.jetbrains.annotations.Nullable;

/**
 * Client snapshot storage. Holds at most one snapshot, for the menu the client is watching.
 *
 * <p>Rules: snapshots for any other menu are ignored; within one session only a strictly newer
 * revision replaces the current one (late or duplicated packets are dropped); a new session for the
 * watched menu replaces everything. Snapshots are immutable, so readers can never mutate state.
 */
public final class DankSnapshotCache<T> {
    public static final int NONE = Integer.MIN_VALUE;

    private int watchedMenu = NONE;
    @Nullable
    private Snapshot<T> current;
    private int changes;

    /** Starts watching a menu and drops any snapshot of a previous one. */
    public void watch(int menuId) {
        if (menuId != watchedMenu) {
            watchedMenu = menuId;
            clear();
        }
    }

    public int watchedMenu() {
        return watchedMenu;
    }

    /** Forgets everything (disconnect, world change, recipe reload). */
    public void invalidate() {
        watchedMenu = NONE;
        clear();
    }

    private void clear() {
        if (current != null) {
            current = null;
            changes++;
        }
    }

    /**
     * Offers a received snapshot.
     *
     * @return true when it became the current snapshot
     */
    public boolean accept(Snapshot<T> snapshot) {
        if (snapshot.menuId() != watchedMenu) {
            return false;
        }
        if (current != null && current.session() == snapshot.session() && snapshot.revision() <= current.revision()) {
            return false;
        }
        current = snapshot;
        changes++;
        return true;
    }

    /** The usable snapshot for {@code menuId}, or null while none has arrived. */
    @Nullable
    public Snapshot<T> current(int menuId) {
        return current != null && current.menuId() == menuId ? current : null;
    }

    /** Increases whenever the usable snapshot changes; lets viewers refresh cached state cheaply. */
    public int changeCount() {
        return changes;
    }
}
