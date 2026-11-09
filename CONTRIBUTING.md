# Contributing

Thanks for wanting to help. Bug reports, feature requests and pull requests are all welcome.

## Setup

Build Forge Table's engine into your local Maven repository, then the plugin, as in the
[build guide](docs/build.md). You need JDK 21 and Maven 3.9 or newer.

## Checks

There is no test suite yet. Before you open a pull request:

- `mvn -B package` must compile cleanly against the Forge Table artifacts. It also runs checkstyle
  (`checkstyle.xml`), which fails on any warning: no tabs, lines at most 140 characters, a newline at end of file, no
  star imports, no unused imports.
- Smoke-test the CLI with a seeded game:

  ```bash
  java -Xmx3g -Xss8m -jar target/lyra-forge-plugin-<version>-all.jar --res ../forge-table/forge-gui/res \
    bench -d A.dck,B.dck -a random,forge-default -n 10 --seed 1
  ```

## Things to know

- The plugin builds against forge-core, forge-game and forge-ai only, never forge-gui.
- The encoder version is a contract with trained models. If you change what `ObservationEncoder` produces, bump
  `ENCODER_VERSION`, or every published model breaks silently.
- `DelegationCounter.java` is generated; don't edit it by hand.
- Keep the docs in step with the code: system properties and the model folder live in
  [docs/configuration.md](docs/configuration.md), CLI commands and seat names in [docs/cli.md](docs/cli.md), the
  package layout and decision surface in [docs/architecture.md](docs/architecture.md). Change them in the same
  commit.
- Commit subjects name the area first, for example `scripts/overlay.sh: ...`.

By contributing you agree your work is released under the project's licence, GPL-3.0-or-later.
