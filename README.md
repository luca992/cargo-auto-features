# cargo-auto-features

A RustRover plugin that rewrites Cargo test run configurations to include the `--features` a test needs to compile. Clicking the gutter arrow on a feature-gated test just works.

> **As much as I hate it: this entire repo, including this readme, is AI generated.** I just needed tests that are gated by features to run in my IDE and didn't care how it got done.

Turns the gutter-generated command

```
test --package my-crate --lib -- gated_module::tests::some_test --exact
```

into

```
test --features gated-feature,mock --package my-crate --lib -- gated_module::tests::some_test --exact
```

### How features are detected

Union of:
* `#[cfg(feature = "...")]` gates on the module chain from the crate root to the test (`mod` declarations, inline `mod` blocks, `#![cfg]` inner attributes, `#[path]` overrides, the test fn itself)
* `required-features` of the addressed `[[test]]`/`[[bin]]`/`[[bench]]`/`[[example]]` target in Cargo.toml
* extra test features (default `mock`) added whenever the package declares them, for features tests need to compile without any cfg gate, like mocks pulled from dependency crates. `package:feature` entries scope an extra to one package. Configurable in Settings | Tools | Cargo Auto Features.

Package directories are resolved from workspace member declarations (globs supported) without shelling out to cargo. Commands that use `--all-features`, `--doc`, `--workspace`, or already have every needed feature are left alone. Hand-written features are kept and merged.

### Installing

1. `./build-plugin.sh`
2. RustRover > Settings > Plugins > gear icon > Install Plugin from Disk > `build/plugin/dist/cargo-auto-features-<version>.zip`

Rebuild and reinstall after a RustRover major update.

### Building

```
./kotlin test        # unit tests, via kotlin-toolchain
./build-plugin.sh    # plugin zip
```

`build-plugin.sh` compiles against a local RustRover installation (override with `RUSTROVER_HOME=/path/to/RustRover.app`, compile against the oldest version you run) using a pinned kotlinc provisioned into `.tools/`, because the IDE jars ship Kotlin metadata newer than most installed compilers can read.

### Notes:
* Mechanism: two project listeners (`RunManagerListener.runConfigurationAdded`, `ExecutionListener.processStartScheduled`) rewrite `CommonProgramRunConfigurationParameters.programParameters` on `CargoCommandRunConfiguration` configurations. The Rust plugin re-parses the command on every set, so only stable platform interfaces are touched and features re-resolve on every launch.
* Modules declared through macros (`cfg_if!` and friends) are not followed.
* Every branch of `any(feature = ...)` is collected, which can enable more features than strictly necessary.
* Whole-package test runs (no test path) only get the extra test features.
