# Decisions

## 2026-10-29 - NeoForge 1.21.1 line on its own branch

The port lives on the long-lived `neoforge-1.21.1` branch, started from Forge `main` at
`233d9fd` (pack baseline `v0.1.0` = `881932c`); `main` keeps Forge 1.20.1. Fixes are cherry-picked
deliberately. Releases are `neoforge-1.21.1-v<version>` with `dank_recipe_transfer-1.21.1-neoforge-<version>.jar`,
starting at 0.2.0, and never become GitHub's "latest" while Forge is the maintenance default.
Rejected: one cross-version source tree (the Minecraft API gap is too wide).

- **Recipes travel as holders.** 1.21 recipes have no id of their own; viewers (JEI 19, EMI 1.21) hand
  over `RecipeHolder<CraftingRecipe>`, and the id sent to the server, the server lookup, the executor
  and Polymorph's selection all keep that holder, so the requested recipe stays the one that wins.
- **Item identity is vanilla's stacking identity.** `ItemKey` keys on a count-1 copy compared with
  `ItemStack.isSameItemSameComponents` and hashed with `hashItemAndComponents`: every component,
  removed defaults and a max-stack-size component included. Rejected: keying on `CUSTOM_DATA` only,
  which would merge differently named or enchanted stacks.
- **Protocol 2, typed optional payloads.** Four `CustomPacketPayload`s (subscribe/request serverbound,
  snapshot/result clientbound), registered `optional()` on the main thread. NeoForge throws when a
  payload is sent to a peer that never negotiated it, so every send checks `hasChannel` first: a
  vanilla client gets nothing and keeps native transfer. Snapshot bounds (2,048 entries, 900,000
  bytes) are measured from the start of the snapshot body. Forge and NeoForge peers never interoperate.
- **Dank 20 auto-sort.** Dank's inventory now re-sorts itself after every change when its auto-sort
  setting is on, so slots move during a transfer. Extraction follows the key to whichever slot holds it,
  and rollback restores a whole-store checkpoint (items and ghost locks) with list replacement instead
  of per-slot writes, which would each trigger another sort. `CarriedDank#restore(slot, stack)` became
  `checkpoint()`; this is the only structural change to the Dank boundary.
- **Viewer hooks kept.** The JEI and EMI mixins keep their targets; their descriptors were re-checked
  against the pinned jars (README "Source pins"). Keeping narrow internal hooks preserves native
  transfer when no Dank item is needed.

## 2026-10-25 - Shared hosted CI and deliberate releases

Adopt the workspace template as byte-identical standalone files. Pushes verify without publishing;
owner-authorized release dispatch after green main CI verifies, builds, drafts, hashes readback
and publishes immutable assets. Gradle resolves the exact release manifest beside the staged jar.

No datagen exists. Real Forge GameTests against Dank Storage gate release; the shared world helper
stages their flat world. The shared `verifyReleaseJar` guard enforces the mixin config and its
optional-viewer gate, manifest activation and MIT license declared in `.ci/mod.json`, replacing
the manual release checklist.

Newest first.

## 2026-10-20 — Accept CraftingMenu subclasses with the vanilla layout; answer every subscription

Found in owner testing (0.1.0): EMI's fill button stayed on "Syncing Dank contents..." forever.
Mods such as EnderIO (painted crafting table), FTB Essentials (`/craft`) and Ars Nouveau open the
crafting-table screen with a server-side `CraftingMenu` subclass that only overrides `stillValid`.
The client builds a plain `CraftingMenu` for that screen, so it subscribed; the server required the
exact class and silently dropped the subscription, so no snapshot ever came. Now the server accepts
any `CraftingMenu`/`InventoryMenu` with the vanilla 46-slot layout, and refuses any other menu with
an explicit "unsupported" reply so the client falls back to native behaviour.

## 2026-10-20 — Mixins live in `compat/mixin/`, not beside their handlers

Mixin refuses to let normal code load any class inside the configured mixin package, so the
handlers (`compat/jei`, `compat/emi`) and the mixins (`compat/mixin/jei`, `compat/mixin/emi`) are
separate packages. The plan had them side by side; that would have crashed at runtime.

## 2026-10-20 — Native transfer whenever no Dank item is needed

If the plan can be satisfied from the grid and loose inventory alone, the adapters hand the click
to JEI's or EMI's own transfer. Only Dank-dependent transfers take the companion path. Keeps
ordinary behaviour (and its interaction with Crafting Tweaks, IPN, Polymorph's own hooks) exactly
as it was.

## 2026-10-20 — Planner is generic over the item key

`TransferPlanner` works on any key type, and `ItemKey` adapts it to Minecraft. The hardest logic
(alternatives, complete sets, returns, conservation) is unit-tested with strings, without a
Minecraft bootstrap; real handlers are covered by GameTests.

## 2026-10-20 — Standalone mod instead of forking Sophisticated JEI Index

SJI's discovery depends on Sophisticated classes and its snapshot cache only copies client handlers;
Dank's client capability is empty, so Dank needs its own server-to-client sync anyway. A fork would
keep JEI packet interception and EMI virtual-slot machinery we do not need. Rejected: fork SJI.

## 2026-10-20 — Rollback may write Dank slots directly; normal extraction never does

Extraction always goes through `extractItem` (simulate, then act, compare). Only a rollback restores
the exact original stack with `setStackInSlot`, because ordinary reinsertion can refuse an item that
was already stored (blacklist added later, ghost lock). Covered by the
`rollbackrestoresblacklisteditems` GameTest.

## 2026-10-20 — Nothing returns into a Dank

Displaced grid items go only to the loose inventory, and the transfer is refused before any move if
they do not fit. Keeps Dank insertion filters and overflow behaviour out of the transfer path.
