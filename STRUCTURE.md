# STRUCTURE — Dank Recipe Transfer

<!-- Last full review: never -->

Code map. Behaviour and protocol: [README.md](README.md).

## Layout

- `.github/workflows/`, `.github/actions/setup-mod-build/` - synced hosted checks and deliberate release dispatch.
- `.ci/mod.json` - this mod's identity, build profile (`neoforge-1.21.1`) and release branch, datagen/GameTest flags, pack side and declarative metadata/mixin/license jar checks; `.ci/port-provenance.json` - branch start, pack baseline and pinned dependency hashes; `.ci/build-profiles.json` and `.ci/scripts/` are synced release tooling.
- `gradle/{build-profile,verify-release-jar,test-world,gametest-world}.gradle` and `tools/test-world/datapack/` (the 1.21.1 datapack, singular `function/` paths) - synced profile selection, jar verification and test-world staging.
- `gradle/wrapper/`, `gradlew*`, `.gitattributes`, `.gitignore` - synced workspace files; edit the root template and sync.
- `docs/releases/` - versioned release notes; add notes only for a version being prepared for release.

- `src/main/java/io/github/dirtyredz/danktransfer/` — mod bootstrap (`DankRecipeTransfer.java`) only
- `src/main/java/io/github/dirtyredz/danktransfer/crafting/` — planning and server execution (no viewer, no Dank calls)
- `src/main/java/io/github/dirtyredz/danktransfer/dank/` — the only code that calls Dank Storage
- `src/main/java/io/github/dirtyredz/danktransfer/network/` — wire format (`TransferMessages`), production item serialization (`ItemPrototypeCodec`), payload registration and dispatch (`TransferChannel`), server request validation
- `src/main/java/io/github/dirtyredz/danktransfer/network/payload/` — typed NeoForge payload wrappers (`CustomPacketPayload` + `StreamCodec`) around the message models
- `src/main/java/io/github/dirtyredz/danktransfer/sync/` — server snapshot subscriptions
- `src/main/java/io/github/dirtyredz/danktransfer/client/` — client preflight, request correlation, snapshot cache, clientbound payload receipt (`ClientPayloadHandler`)
- `src/main/java/io/github/dirtyredz/danktransfer/compat/` — optional integrations (`jei/`, `emi/`, `polymorph/`) and the mixin gate
- `src/main/java/io/github/dirtyredz/danktransfer/compat/mixin/` — the mixin package (`jei/`, `emi/`); nothing else may live here
- `src/main/resources/` — `META-INF/neoforge.mods.toml` (dependencies and `[[mixins]]`), mixin config, lang
- `src/test/java/io/github/dirtyredz/danktransfer/` — pure JUnit tests (no Minecraft bootstrap)
- `src/gameTest/java/io/github/dirtyredz/danktransfer/` — NeoForge GameTests (`crafting/ItemKeyGameTests` beside the package it tests); never in the release jar
- `src/gameTest/resources/` — GameTest structure (`data/dank_recipe_transfer/structure/empty.nbt`) and a test-only Dank blacklist tag (`data/dankstorage/tags/item/blacklisted_storage.json`)
- `docs/` — design decisions

## Components

| Component | Files | Responsibility |
|---|---|---|
| Planner | `crafting/PlanInput`, `TransferPlan`, `TransferPlanner` | Generic, side-effect-free allocation over any item key type; shared by client and server; unit-tested with strings |
| Minecraft adapters | `crafting/ItemKey`, `RecipePattern`, `CraftingMenuAccess` | Item + full data-component identity (vanilla stacking rules); recipe placement into a grid; exact vanilla menu layouts and planner input |
| Executor | `crafting/TransferExecutor`, `TransferOutcome` | Live replan against a `RecipeHolder`, journaled mutation (whole-store Dank checkpoints), conservation and recipe checks, rollback |
| Dank boundary | `dank/CarriedDank`, `DankSource` | Discovery, validation, capped extraction that follows Dank's auto-sort, whole-store rollback checkpoints |
| Protocol | `network/TransferMessages`, `ItemPrototypeCodec`, `payload/*`, `TransferChannel`, `ServerRequestHandler` | Bounded, Minecraft-free framing; registry-aware item prototypes; typed optional payloads (protocol 2), negotiated-peer sends and main-thread dispatch; request validation |
| Sync | `sync/DankSnapshotService` | Subscriptions, revisions, change detection, request-id dedupe |
| Client core | `client/ClientTransfer`, `DankSnapshotCache`, `PendingTransfer`, `ClientLifecycle`, `ClientPayloadHandler` | Preflight, one in-flight request, exactly-once completion, subscription upkeep, invalidation |
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
- All mixins target other mods' classes with `remap = false`; NeoForge runs on official names, so there is no refmap and no reobfuscation.
- `TransferMessages` stays free of NeoForge network types so its framing is unit-tested with string prototypes; `ItemPrototypeCodec` and `network/payload/` are the only registry-aware parts, exercised by `SnapshotWireGameTests`.
- ModDevGradle groups the `main` and `gameTest` outputs under one mod id in development, so dev runs load the GameTest classes and resources; only `gameTestServer` enables the namespace, and the release jar is built from `main` alone (the jar guard rejects anything else).
- `TransferPlanner` is generic over the key type on purpose: it keeps the hardest logic testable
  without a Minecraft bootstrap.
