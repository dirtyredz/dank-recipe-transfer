# Gotchas

- **A successful mixin injection is not necessarily valid bytecode.** JEI 19.70.0.464 reuses the
  `container` and `category` argument slots inside
  `RecipeTransferManager#getRecipeTransferHandler`: they hold `MenuType` and `Optional` at RETURN.
  A RETURN injector whose handler reloads those original arguments applies without an injection
  error but produces a client-only `VerifyError`. Use an exact-descriptor `@WrapMethod` (or another
  hook that receives the arguments before slot reuse), and keep `ClientMixinVerificationTest` in
  the `test` gate so every pinned JEI/EMI target is transformed and ASM-verified headlessly.
