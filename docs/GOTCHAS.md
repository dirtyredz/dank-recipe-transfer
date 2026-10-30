# Gotchas

- **A successful mixin injection is not necessarily valid bytecode.** JEI 19.70.0.464 reuses the
  `container` and `category` argument slots inside
  `RecipeTransferManager#getRecipeTransferHandler`: they hold `MenuType` and `Optional` at RETURN.
  A RETURN injector whose handler reloads those original arguments applies without an injection
  error but produces a client-only `VerifyError`. Use an exact-descriptor `@WrapMethod` (or another
  hook that receives the arguments before slot reuse), and keep `ClientMixinVerificationTest` in
  the `test` gate so every pinned JEI/EMI target is transformed and ASM-verified headlessly.
- **The headless verifier intentionally reflects into Mixin internals.** `ClientMixinHarness`
  constructs `MixinTransformer` and registers an environment-specific configuration through
  non-public APIs because Mixin exposes no supported standalone transformer entry point. If a
  Mixin or MixinExtras bump breaks this harness while the pinned mixins are unchanged, update the
  harness for the new internals; do not treat that breakage alone as a mod regression.
