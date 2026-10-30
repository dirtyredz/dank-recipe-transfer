# Dank Recipe Transfer

NeoForge 1.21.1 companion mod (branch `neoforge-1.21.1`; the Forge 1.20.1 line stays on `main`): the JEI and EMI recipe-transfer button ("+") fills the vanilla
crafting table and the player's 2x2 inventory grid from what is already in the grid, then loose
inventory, then any **Dank Storage** you carry. Transfers run on the server, are validated, and are
all-or-nothing.

Generic: not tied to any modpack.

## Supported versions

| Component | Version | Required |
|---|---|---|
| Minecraft | 1.21.1 | yes |
| NeoForge | 21.1.265 or a later 21.1 build | yes |
| Dank Storage | build `20` for 1.21.1 (CurseForge 335673, file 7539725) | yes, both sides |
| JEI | 19.70.0.464 | optional, client |
| EMI | 1.1.24+1.21.1 | optional, client |
| Polymorph | 1.2.0+1.21.1 (CurseForge 388800, file 8849478) | optional |

**Install on both the server and every client.** If either side lacks the mod, the viewers fall
back to their normal behaviour: the payloads are registered optional, and nothing is sent to a
peer that did not negotiate them. The wire protocol is version `2` and does not interoperate with
the Forge 1.20.1 build (`1`); worlds carry no Dank Recipe Transfer data, so nothing converts.
Sophisticated Backpacks is not needed and not used.

## Behaviour

- Works on the vanilla `CraftingMenu` (crafting table) and `InventoryMenu` (2x2 grid), including
  subclasses that keep the vanilla layout (EnderIO painted crafting table, FTB Essentials `/craft`),
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
- Dank rules are Dank's own. Dank 20 stores start with auto-sort on: the store re-sorts after every
  change, and its sort keeps a ghost (locked) filter only on slots that still hold items, so emptying a
  locked slot (by hand or by a transfer) drops that lock exactly as Dank does natively. With auto-sort
  off, ghost slots keep their filter after being emptied; pickup and
  construction modes do not block extraction; a lower-tier Dank linked to higher-tier storage is
  ignored, like Dank refuses to open it. Discovery never creates, upgrades or relinks storage.
- The button reflects Dank contents only after the server sends the first snapshot (a few ticks
  after the grid opens). Until then the viewer behaves natively and Dank-only recipes show as
  missing. EMI's button refreshes on its own when the snapshot arrives. A menu the server cannot
  serve gets an explicit "unsupported" reply, and the viewer stays native.
- Item identity is the item plus its complete data components (names, enchantments, custom data,
  removed defaults, a max-stack-size component), never the count, exactly like vanilla stacking.
  Different components are different ingredients and different snapshot lines.
- Diagnostics: subscription accept/refuse (server) and snapshot receipt (client) are logged at
  DEBUG, so they appear in `logs/debug.log`.

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

Requires only a JDK to run Gradle; the Java 21 toolchain is provisioned automatically.
The build uses ModDevGradle 2.0.148 (official mappings).

```
./gradlew test build verifyReleaseJar   # unit tests + client-mixin bytecode check + release jar guard
./gradlew runGameTestServer             # headless NeoForge server GameTests against real Dank Storage
git diff --check
```

- Release jar: `build/libs/dank_recipe_transfer-1.21.1-neoforge-<version>.jar` (no reobfuscation on NeoForge). GameTests and
  unit tests live in their own source sets and are not in it; no dependency is bundled.
- `-PwithViewers=jei,emi` (either or both) puts the viewers on the dev runtime classpath, for
  running the GameTest server with them installed; `-PwithPolymorph` adds Polymorph.
- Never run `runClient` from automation; client behaviour is verified by a person in game.

## Release

The version lives only in `gradle.properties`. Prepare non-empty `docs/releases/<version>.md`
for a release, then obtain the owner's go-ahead to push the `neoforge-1.21.1` branch (the
release branch declared in `.ci/mod.json`; never merged into the Forge `main`). Push CI runs
`./gradlew test build verifyReleaseJar`; it publishes nothing.
After green main CI and owner authorization, the agent dispatches
`gh workflow run release.yml --ref neoforge-1.21.1`; any other branch is refused. The workflow verifies
again, requires successful NeoForge GameTests, builds the final jar, creates its `neoforge-1.21.1-v<version>` tag, uploads a draft, reads every asset back and compares
SHA-256 before publishing the jar, its `.sha256` and `dank_recipe_transfer.pw.toml`.
The pack session consumes the published `.pw.toml`; the owner never publishes by hand.
Versions starting with `0.` or containing `-` are prereleases. Existing releases/tags are
refused, published bytes are never replaced, and a NeoForge release never becomes GitHub's
repository-wide "latest" while Forge is the maintenance default.
A failed draft requires deliberate recovery before retrying.

Local fast gate: `./gradlew --offline test build verifyReleaseJar` with cached dependencies.
Shared files are checked from the workspace with `tools/sync-mod-files.ps1 -Check -Mod <this-worktree>`.

CI sets `profile: neoforge-1.21.1`, `releaseBranch: neoforge-1.21.1`, `datagen: false` and `gametests: true`
(the real NeoForge suite against Dank Storage).
The `test` task applies every client mixin to the pinned JEI/EMI implementation jars and verifies
the transformed classes with ASM before the shared `verifyReleaseJar` guard checks metadata and
excludes non-main classes/test resources.
It requires `neoforge.mods.toml` (refusing Forge's `mods.toml`), its `[[mixins]]` declaration, the mixin
config with its optional-viewer gate and `JAVA_21` level, no manifest `MixinConfigs`, and the MIT license,
as declared in `.ci/mod.json`. `.ci/port-provenance.json` (branch start, pack baseline, pinned dependency
hashes) travels into `build/ci/release.json`. These automated
checks are authoritative in both the local fast gate and hosted CI.

## Source pins

The integration points were read with `javap` from these exact pinned jars (2026-10-29; SHA-256 in
`.ci/port-provenance.json`):

- Dank Storage 20 (`curse.maven:dank-storage-335673:7539725`): `DankItem` (frequency is the
  `dankstorage:frequency` component, `stats` tier), `DankSavedData#get` (non-creating;
  `DimensionDataStorage#get` under `dankstorage/<frequency>`), `DankInventoryForge` (NeoForge
  `IItemHandlerModifiable`; extraction capped at the stack's max size), `DankInventory` (ghost
  stacks, frequency lock, and an auto-sort that re-sorts on every change). The Forge build's
  `CDankItem`/`CDankSavedData`/`DankInterface` no longer exist; the adapter was rewritten, not renamed.
- JEI 19.70.0.464: `RecipeTransferManager#getRecipeTransferHandler(C, IRecipeCategory)`,
  `BasicRecipeTransferHandler`, `PlayerRecipeTransferHandler` (now typed on
  `RecipeHolder<CraftingRecipe>`), `IRecipeTransferHandlerHelper#getGuiSlotIndexToIngredientMap(RecipeHolder)`.
- EMI 1.1.24+1.21.1: `EmiRecipeFiller#getAllHandlers`, `RecipeFillButtonWidget#canFill/tooltip`,
  `RecipeButtonWidget#recipe`, `EmiRecipe#getBackingRecipe` (a `RecipeHolder`).
- Polymorph 1.2.0+1.21.1: `PolymorphApi#getPlayerRecipeData`, `IRecipeData#selectRecipe(RecipeHolder)`
  (stores the selection, clears its cache and syncs the player).

## Credits

Design reference: [Sophisticated JEI Index](https://github.com/AmicBeam/Sophisticated-JEI-Index)
(MIT) by AmicBeam, which does the same for Sophisticated Backpacks. This mod is an original
implementation; no SJI code is copied.

## License

MIT. See [LICENSE](LICENSE).
