# Decisions

Newest first.

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
