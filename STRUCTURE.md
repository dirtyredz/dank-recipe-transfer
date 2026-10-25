# STRUCTURE — Dank Recipe Transfer

<!-- Last full review: never -->

Code map. Behaviour and protocol: [README.md](README.md).

## Layout

- `.github/workflows/`, `.github/actions/setup-mod-build/` - synced hosted checks and deliberate release dispatch.
- `.ci/mod.json` - this mod's identity, datagen/GameTest flags, pack side and declarative mixin/manifest/license jar checks; `.ci/scripts/` is synced release tooling.
- `gradle/{verify-release-jar,test-world,gametest-world}.gradle` and `tools/test-world/datapack/` - synced jar verification and test-world staging.
- `gradle/wrapper/`, `gradlew*`, `.gitattributes`, `.gitignore` - synced workspace files; edit the root template and sync.
- `docs/releases/` - versioned release notes; add notes only for a version being prepared for release.

- `src/main/java/io/github/dirtyredz/danktransfer/` — mod bootstrap (`DankRecipeTransfer.java`) only
- `src/main/java/io/github/dirtyredz/danktransfer/crafting/` — planning and server execution (no viewer, no Dank calls)
- `src/main/java/io/github/dirtyredz/danktransfer/dank/` — the only code that calls Dank Storage
- `src/main/java/io/github/dirtyredz/danktransfer/network/` — wire format, channel, server request validation
- `src/main/java/io/github/dirtyredz/danktransfer/sync/` — server snapshot subscriptions
- `src/main/java/io/github/dirtyredz/danktransfer/client/` — client preflight, request correlation, snapshot cache
- `src/main/java/io/github/dirtyredz/danktransfer/compat/` — optional integrations (`jei/`, `emi/`, `polymorph/`) and the mixin gate
- `src/main/java/io/github/dirtyredz/danktransfer/compat/mixin/` — the mixin package (`jei/`, `emi/`); nothing else may live here
- `src/main/resources/` — mods.toml, mixin config, lang
- `src/test/java/io/github/dirtyredz/danktransfer/` — pure JUnit tests (no Minecraft bootstrap)
- `src/gameTest/java/io/github/dirtyredz/danktransfer/` — Forge GameTests; never in the release jar
- `src/gameTest/resources/` — GameTest structure and a test-only Dank blacklist tag
- `docs/` — design decisions

## Components

| Component | Files | Responsibility |
|---|---|---|
| Planner | `crafting/PlanInput`, `TransferPlan`, `TransferPlanner` | Generic, side-effect-free allocation over any item key type; shared by client and server; unit-tested with strings |
| Minecraft adapters | `crafting/ItemKey`, `RecipePattern`, `CraftingMenuAccess` | Item+NBT identity; recipe placement into a grid; exact vanilla menu layouts and planner input |
| Executor | `crafting/TransferExecutor`, `TransferOutcome` | Live replan, journaled mutation, conservation and recipe checks, rollback |
| Dank boundary | `dank/CarriedDank`, `DankSource` | Discovery, validation, capped extraction, rollback-only restore |
| Protocol | `network/TransferMessages`, `TransferChannel`, `ServerRequestHandler` | Bounded codecs; channel registration and dispatch; request validation |
| Sync | `sync/DankSnapshotService` | Subscriptions, revisions, change detection, request-id dedupe |
| Client core | `client/ClientTransfer`, `DankSnapshotCache`, `PendingTransfer`, `ClientLifecycle` | Preflight, one in-flight request, exactly-once completion, subscription upkeep, invalidation |
| JEI | `compat/jei/*`, `compat/mixin/jei/JeiTransferLookupMixin` | Decorate the native table/inventory handlers at lookup |
| EMI | `compat/emi/*`, `compat/mixin/emi/*` | Decorate native handlers in `getAllHandlers`; refresh the cached fill button |
| Polymorph | `compat/polymorph/PolymorphSelection` | Select the requested recipe before the result recomputes |

## Dependency direction

`compat/*` -> `client` -> `crafting` <- `network` / `sync` -> `dank`. `crafting` never imports a
viewer; only `dank/DankSource` imports Dank classes (plus `TransferExecutor`'s default discovery and
the GameTests). Viewer classes load only through mixins that `compat/MixinGate` enables on the
client when that viewer is installed.

## Structural notes

- Mixins must live in `compat/mixin/`: Mixin forbids normal code from loading classes in the mixin
  package, so handlers stay in `compat/jei` and `compat/emi`.
- All mixins target other mods' classes with `remap = false`, so no refmap is generated.
- `TransferPlanner` is generic over the key type on purpose: it keeps the hardest logic testable
  without a Minecraft bootstrap.
