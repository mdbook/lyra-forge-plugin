# AGENTS.md

A guide for coding agents working in this repo. `README.md` and `docs/` are the user-facing documentation; this file covers what
you need to change the code safely.

## What this is

A Java plugin for Forge Table (a fork of [Forge](https://github.com/Card-Forge/forge)) that adds an AI seat driven by
an ONNX policy network (Lyra). The same code base holds the headless runner the policy was trained with: seeded games
without a GUI, self-play workers that write training trajectories, benchmarking and replay tools.

- Language: Java 21 (`maven.compiler.release` in `pom.xml`).
- Build: Maven 3.9 or newer. Single module, no wrapper script.
- Licence: GPL-3.0-or-later (`LICENSE`). Model weights are released separately and are not in this repo.
- Status: alpha. Settings, saved data and the plugin interface may change between releases.

## Layout

```
pom.xml              build: forge-ai dependency, ONNX Runtime, gson, jeromq; checkstyle; shade (fat jar)
checkstyle.xml       style and import rules, run in the validate phase
scripts/overlay.sh   packs the plugin jar and a Lyra model into an overlay zip for a Forge Table release
src/main/java/mtgai/ all source, one package per area (see docs/architecture.md)
src/main/resources/  tinylog.properties, update-signing.pub (public key that verifies update pointers)
```

Main entry points:

- `mtgai.humanseat.LobbyPlayerNeural`: the seat Forge Table creates by class name for the `Neural` AI profile.
- `mtgai.cli.Main`: the fat jar's main class (bench, play, eval, validate-decks, vocab, selfplay, replay).
- `mtgai.humanseat.UpdateCheck`: run by the release launchers with only `lib/` on the class path.

## Build

The plugin needs `forge-core`, `forge-game` and `forge-ai` from Forge Table in your local Maven repo. They are not
on Maven Central. Install them first.

Known interim gap: Forge Table's `v0.1.0-alpha.1` tag is only created when this plugin goes public, so the tag does
not exist yet, and no public, pinned Forge Table commit has been confirmed. Until the tag is published, build from a
Forge Table checkout you already have (any commit that has the `Neural` AI profile and the `forge.mtgai.*` flags),
and once the tag exists check out `v0.1.0-alpha.1`. Do not float on a branch name in docs.

```bash
cd <your forge-table checkout>        # tag v0.1.0-alpha.1 once it is published
mvn -B -pl forge-ai -am -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true install
cd ..
```

Then build the plugin from the repo root:

```bash
mvn -B package
```

Output in `target/`: `lyra-forge-plugin-<version>.jar` (plugin only) and `lyra-forge-plugin-<version>-all.jar`
(shaded fat jar with Forge and every dependency, for the CLI). If the Forge Table tag declares a different version
than `forge.version` in `pom.xml`, pass `-Dforge.version=<version>`.

If the Forge artifacts are missing, the build fails at dependency resolution. That is an environment problem, not a
code problem; do not change `pom.xml` to work around it.

## Tests and checks

There is no test source tree (`src/test`) and no CI config in this repo. The checks that exist:

- `mvn -B validate` runs checkstyle (`checkstyle.xml`) and fails on any warning. `mvn -B package` runs it too.
- `mvn -B package` must compile cleanly against the Forge Table artifacts.
- Smoke-test the CLI with a seeded game, using a Forge `res/` directory (Forge Table's `forge-gui/res`):

```bash
java -Xmx3g -Xss8m -jar target/lyra-forge-plugin-<version>-all.jar --res ../forge-table/forge-gui/res \
    bench -d A.dck,B.dck -a random,forge-default -n 10 --seed 1
java -jar target/lyra-forge-plugin-<version>-all.jar    # no arguments: the full option list
```

Run with `-Xss8m`; Forge needs the larger thread stack.

## Conventions

- Match the existing style (4-space indent). Checkstyle enforces: no tabs, lines at most 140 characters, a newline
  at end of file, no star imports, no unused or redundant imports.
- An empty catch block must name its exception `ignored`.
- A class that overrides `equals` must override `hashCode`.
- Comments and Javadoc are plain and factual; they describe what the code does and why.
- Keep the docs in step with the code: system properties and the model folder layout are in `docs/configuration.md`,
  CLI commands and seat names in `docs/cli.md`, the package layout and decision surface in `docs/architecture.md`.
  Change them in the same commit when you change those.
- Commit subjects name the area first, for example `scripts/overlay.sh: ...`.

## Gotchas

- **No forge-gui.** The plugin builds against forge-core, forge-game and forge-ai only. Checkstyle rejects imports
  from `forge.gui`, `forge.model`, `forge.localinstance`, `forge.player` and `forge.gamemodes`.
- **Stock Forge will not load this.** It relies on hooks only Forge Table has (the `Neural` AI profile, the
  `forge.mtgai.*` engine flags).
- **`DelegationCounter.java` is generated** from `javap` output of `forge.game.player.PlayerController`. Do not edit
  it by hand. Checkstyle skips it.
- **`scripts/overlay.sh` reads two constants from the sources with `sed`:**
  `ObservationEncoder.ENCODER_VERSION` (`public static final int ENCODER_VERSION = N;`) and
  `ArchetypeClassifier.DECK_SET` (`public static final String DECK_SET = "...";`). Keep each on one line in that
  form, or the script stops.
- **Encoder version is a compatibility contract with trained models.** A model's `latest.json` `encoder_version` must
  be one this build can encode for (currently 10; `OLDEST_MODEL` also pins at 10, so no older encoder is accepted
  right now). Changing what `ObservationEncoder` produces without bumping the version breaks every published model
  silently.
- **`DecisionRequest` holds at most 64 candidates.** Keep that limit in mind when adding candidate kinds.
- **`ForgeBootstrap` sets `forge.mtgai.noGc=true` and `forge.mtgai.inlineAiEval=true`** unless they are already set.
- **The ONNX Runtime version (1.30.0) matches the Python learner.** Do not bump it on its own.
- **Update checks are signed.** `UpdateCheck` verifies an Ed25519 signature on every pointer against
  `src/main/resources/update-signing.pub`. There is no unsigned fallback; do not add one. `HOST` is the
  `updates.invalid` placeholder in this public source on purpose; distributors set their own.

## Release path

- The version lives in `pom.xml` (`<version>`). Releases so far bump it in a commit (for example `0.1.0-alpha.1`).
- `mvn -B package`, then `scripts/overlay.sh --model <lyra-model zip or dir> [--out DIR|FILE.zip]` builds
  `target/lyra-forge-plugin-<version>-overlay.zip` plus a `.sha256`. It needs `zip`, `unzip`, `python3` and
  `sha256sum`, takes ONNX Runtime from the local Maven repo (`MAVEN_REPO`, default `~/.m2/repository`), and refuses a
  model whose sha256 or `encoder_version` does not match this build.
- Forge Table's `package.sh --overlay ZIP` unpacks that zip over a Forge Table release. The overlay never contains
  `forge-*` jars; the table ships those.
- Nothing in this repo deploys anything. There is no CI pipeline here.

## Do not touch

- `LICENSE`, and the licence and Fan Content notices in `README.md`.
- `src/main/resources/update-signing.pub`, the `HOST`/`PACKAGES` placeholders in `UpdateCheck`, and the signature
  check, unless the task is explicitly about update signing.
- `DelegationCounter.java` (generated).
- Dependency versions in `pom.xml` and the checkstyle rules, unless the task is about them.
- Model files, trained weights and deck sets: they are not part of this repo, so do not commit them here.
- Never commit secrets, signing private keys, private hostnames or local paths. This is public open source.
