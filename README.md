# Dank Recipe Transfer

Forge 1.20.1 companion mod: the JEI and EMI recipe-transfer button ("+") fills the vanilla
crafting table and the player's 2x2 inventory grid from what is already in the grid, then loose
inventory, then any **Dank Storage** you carry. Transfers run on the server, are validated, and are
all-or-nothing.

Generic: not tied to any modpack.

## Supported versions

| Component | Version | Required |
|---|---|---|
| Minecraft | 1.20.1 | yes |
| Forge | 47.x (built and tested on 47.4.10) | yes |
| Dank Storage | `dankstorage-forge-1.20.1-16` (CurseForge 335673, file 7300650) | yes, both sides |
| JEI | 15.62.0.219 | optional, client |
| EMI | 1.1.24+1.20.1 | optional, client |
| Polymorph | 0.49.11+1.20.1 (CurseForge 388800, file 8854504) | optional |

**Install on both the server and every client.** If either side lacks the mod, the viewers fall
back to their normal behaviour (the channel accepts a missing peer). Sophisticated Backpacks is not
needed and not used.

## Behaviour

- Works only on the exact vanilla `CraftingMenu` (crafting table) and `InventoryMenu` (2x2 grid),
  with registered shaped and shapeless crafting recipes. Special recipes (dyeing, map cloning...),
  modded crafting stations, AE2 terminals, Curios and nested containers keep native behaviour.
- **Sources, in order:** items already in the grid, then the 36 loose inventory slots (main
  inventory before hotbar), then each distinct Dank: carriers in the 36 slots, then the offhand.
  Several carriers on one frequency count once. The carriers themselves are never ingredients.
- **"+"** places one complete set. If the grid already holds exactly this recipe, it adds one set on
  top. **Shift / max** places as many complete sets as the ingredients and stack sizes allow.
  EMI's finite amounts are honoured (capped to what is available). Output is never taken.
- Items already in the grid that the recipe does not reuse go back to the loose inventory. If they
  do not fit (counting slots freed by the transfer), the transfer is refused before anything moves.
  Nothing is ever put back into a Dank.
- If a plan needs no Dank items, the viewer's own native transfer runs instead (JEI's packet, EMI's
  fill), so ordinary transfers behave exactly as before.
- Dank rules are Dank's own: ghost (locked) slots keep their filter after being emptied; pickup and
  construction modes do not block extraction; a lower-tier Dank linked to higher-tier storage is
  ignored, like Dank refuses to open it. Discovery never creates, upgrades or relinks storage.
- The button reflects Dank contents only after the server sends the first snapshot (a few ticks
  after the grid opens). Until then the viewer behaves natively and Dank-only recipes show as
  missing. EMI's button refreshes on its own when the snapshot arrives.

## How it works

```
client (JEI / EMI adapter)            server
  preflight: TransferPlanner  <---- Snapshot(menu, session, revision, item totals)  <- DankSnapshotService
  "+" ----------------------------> TransferRequest(id, menu, session, revision, recipe, batches)
                                      ServerRequestHandler: validate menu, session, revision, recipe, quantity
                                      TransferExecutor: rediscover Danks, replan, extract under a journal,
                                                        verify conservation + recipe match, or roll back
  completeRecipeTransfer <-------- TransferResult(id, outcome)
```

- Snapshots are sent only while a supported grid is open, checked at most every 5 server ticks,
  and only when contents change; immediately after each transfer too. Requests never carry item
  grants: the server rediscovers sources and replans from live state.
- One transfer in flight per client; duplicate request ids are rejected; a request planned against
  an old snapshot revision is rejected as stale (no automatic retry).
- Polymorph, when present, is told which recipe was requested before the result is recomputed.

See [STRUCTURE.md](STRUCTURE.md) for the code map and [docs/DECISIONS.md](docs/DECISIONS.md) for why.

## Build and test

Requires only a JDK to run Gradle; the Java 17 toolchain is provisioned automatically.

```
./gradlew test build          # unit tests + reobfuscated release jar
./gradlew runGameTestServer   # headless Forge server GameTests against real Dank Storage
git diff --check
```

- Release jar: `build/libs/dank_recipe_transfer-1.20.1-<version>.jar` (reobfuscated). GameTests and
  unit tests live in their own source sets and are not in it; no dependency is bundled.
- `-PwithViewers=jei,emi` (either or both) puts the viewers on the dev runtime classpath, for
  running the GameTest server with them installed.
- Never run `runClient` from automation; client behaviour is verified by a person in game.

## Release

1. Bump `mod_version` in `gradle.properties` (the only place the version lives).
2. `./gradlew test build runGameTestServer`, then inspect the jar (mods.toml, mixin config,
   `META-INF/LICENSE`, no `io/github/dirtyredz/danktransfer/*GameTests*` classes, no `mezz/`,
   `dev/emi/` or `tfar/` classes).
3. Tag `v<version>` and attach the jar to an immutable GitHub release (prerelease first).
4. Packs reference the release asset by URL and SHA-256, `side = "both"`.

## Source pins

The integration points were read at these exact sources (recorded 2026-10-20):

- Dank Storage `Tfarcenim/Dank-Storage` branch `1.20.1-multiloader`, `gradle.properties version=16`
  (`DankItemCapability`, `DankInventoryForge`, `CDankSavedData`, `PortableDankProvider`).
- JEI tag `v15.62.0` (`RecipeTransferManager`, `BasicRecipeTransferHandler`,
  `PlayerRecipeTransferHandler`, `IRecipeTransferContext`); signatures re-checked with `javap`
  against `jei-1.20.1-forge-15.62.0.219.jar`.
- EMI tag `1.1.24+1.20.1` (`EmiRecipeFiller#getAllHandlers`, `RecipeFillButtonWidget`,
  `CraftingRecipeHandler`, `InventoryRecipeHandler`); re-checked against `emi-1.1.24+1.20.1+forge.jar`.
- Polymorph `0.49.11+1.20.1` jar: `PlayerRecipeData#selectRecipe` clears its cache, stores the
  recipe and syncs the player.

## Credits

Design reference: [Sophisticated JEI Index](https://github.com/AmicBeam/Sophisticated-JEI-Index)
(MIT) by AmicBeam, which does the same for Sophisticated Backpacks. This mod is an original
implementation; no SJI code is copied.

## License

MIT. See [LICENSE](LICENSE).
