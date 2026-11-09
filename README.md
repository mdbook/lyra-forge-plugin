# lyra-forge-plugin

A [Forge](https://github.com/Card-Forge/forge) AI seat driven by a neural network. The seat plays Magic: The
Gathering with an ONNX policy (Lyra) that was trained by self-play inside the Forge rules engine. Pick it in a
Forge Table lobby like any other AI opponent: the policy makes the game decisions it was trained on, and
everything else (mana payment details, trigger ordering and so on) falls back to Forge's own AI.

The same code also contains the headless runner the policy was trained with: seeded games without a GUI,
self-play workers that write training trajectories, and a few benchmarking and replay tools.

**This is alpha software.** Expect bugs and rough edges. Settings, saved data and the plugin interface may change
between releases. Bug reports and feature requests are welcome.

## Licence

Copyright (C) 2026 Mik Burke

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General
Public License as published by the Free Software Foundation, either version 3 of the License, or (at your
option) any later version. It links directly against Forge's forge-core, forge-game and forge-ai packages. This
program is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See `LICENSE` for the full text.

The trained model weights (Lyra) are released separately under AGPL-3.0, with their own notice.

Lyra and lyra-forge-plugin are unofficial Fan Content permitted under the Fan Content Policy. Not
approved/endorsed by Wizards. Portions of the materials used are property of Wizards of the Coast. ©Wizards of
the Coast LLC.

## Requirements

- JDK 21 and Maven 3.9 or newer.
- The Forge Table fork of Forge, which adds the hooks this plugin needs (the `Neural` AI profile, the
  `forge.mtgai.*` engine flags, and so on). Stock Forge won't load it.

## Build

The plugin depends on `forge-core`, `forge-game` and `forge-ai` from Forge Table (version `2.0.15-SNAPSHOT`, set
by `forge.version` in `pom.xml`). Install them into your local Maven repository first, then build the plugin:

Forge Table's own release tag doesn't exist yet, so this pins a known-good commit instead; once a tag is cut,
swap it in for `1a127f06d02f3e8085ee4100b0b81160c8250e2e` below.

```bash
# 1. the engine: Forge Table, installed into ~/.m2
git clone https://github.com/mdbook/forge-table
cd forge-table && git checkout 1a127f06d02f3e8085ee4100b0b81160c8250e2e
mvn -B -pl forge-ai -am -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true install
cd ..

# 2. the plugin
cd lyra-forge-plugin
mvn -B package
```

This produces two jars in `target/`:

- `lyra-forge-plugin-<version>.jar`: the plugin on its own, for Forge Table's class path.
- `lyra-forge-plugin-<version>-all.jar`: a fat jar with Forge and every dependency, for the headless CLI.

If the Forge Table tag you build against declares a different version, pass it with `-Dforge.version=<version>`.

### Overlay zip for a Forge Table release

`scripts/overlay.sh` packs the plugin and a Lyra model into a zip that unpacks over a Forge Table release folder
(Forge Table's `package.sh --overlay ZIP` does that for you). Run it after `mvn package`:

```bash
scripts/overlay.sh --model lyra-model-v58625.zip [--out DIR|FILE.zip]   # default: target/lyra-forge-plugin-<version>-overlay.zip
```

### Getting the model

The Lyra weights are a separate release asset, `lyra-model-v<N>.zip` (for example `lyra-model-v58625.zip`),
intended to be attached to the same release as this plugin's source.

**Known interim gap.** No weights asset has been published yet, so there is no download URL and no zip checksum to
give here (none is invented). When the release exists, its page will carry the asset link and the zip's sha256; then:

```bash
sha256sum lyra-model-v<N>.zip        # must equal the sha256 on the release page
unzip lyra-model-v<N>.zip -d lyra-model
```

Inside, `latest.json` carries the sha256 of the `.onnx` policy. The seat and `scripts/overlay.sh` check the model
against that hash, so a corrupt or mismatched file is rejected with a clear error. `UpdateCheck` checks a downloaded
model against the sha256 in the signed update pointer instead.

`--model` takes the model release asset as a zip or unpacked. The overlay holds `lib/` (the plugin jar and ONNX
Runtime, from your local Maven repo; set `MAVEN_REPO` if it isn't `~/.m2/repository`), `neural/` (the model folder,
with `"name": "Lyra"` added to `latest.json`), `decks/` (the decks `latest.json` lists) and the licence files. No
Forge jars: the table ships those. The script stops if the model's sha256 or `encoder_version` doesn't match this
build.

## Using the seat in Forge Table

Forge Table creates the seat by class name (`mtgai.humanseat.LobbyPlayerNeural`, overridable with
`-Dforge.mtgai.neuralPlayer`) whenever a lobby slot uses the `Neural` AI profile. The plugin jar and ONNX
Runtime (`com.microsoft.onnxruntime:onnxruntime` 1.30.0) must be on the game's class path; Forge Table release
builds already include both.

### The model folder

The seat loads its model once per game session from the folder named by `-Dmtgai.neural.dir` (default `neural`,
relative to Forge's working directory). The folder looks like this:

```
neural/
  latest.json             {"version": 58625, "file": "policy-058625.onnx", "sha256": "...", "encoder_version": 10}
  policy-058625.onnx      the policy network (the file latest.json names)
  registry.tsv            card name -> id table for every card (preferred when present)
  cardvocab-v1.txt        the older, smaller card id list (used when registry.tsv is missing)
  2026-09-28/             the archetype deck set: the Pauper deck lists (.dck) the opponent classifier knows
  avatar.png              optional: the seat's in-game picture
```

- `latest.json` may also carry a `decks` list (the decks the model pilots best, strongest first).
- The model is checked against `sha256` when present, and its `encoder_version` must be one this build can
  encode for (currently 10; no older encoder is accepted right now).
- The deck set folder name is `ArchetypeClassifier.DECK_SET`. If it isn't next to the card id file, the seat
  also looks in `../pauper/<DECK_SET>/`.
- `avatar.png` is a Forge Table feature; the plugin doesn't read it.

The Lyra release asset ships this folder ready to use.

### Useful system properties

| property | what |
|---|---|
| `-Dmtgai.neural.dir=DIR` | the model folder (default `neural`) |
| `-Dmtgai.control=damage,order,playdraw,effects,mana,floatb` | the decision groups the policy owns; use the set the model was trained with (the one shown is Lyra's) |
| `-Dforge.mtgai.aiCastRemAIDeck=true` | Forge's AI keeps every card of its deck castable, as in training |
| `-Dmtgai.neural.greedy=false` | sample the policy instead of always taking its top choice (default greedy) |
| `-Dmtgai.neural.log=true` | print the seat's decisions (except passing priority) and a game-over line |
| `-Dmtgai.neural.games=DIR` | where per-game records go (default `userdata/mtgai-games`; empty = none) |

Run the game with a larger thread stack (`-Xss8m`), as Forge Table's launchers do.

### Try it

1. Build the plugin and make the overlay (see above), then unzip the overlay over a Forge Table release folder.
2. Start Forge Table with its normal launcher.
3. Open a new game lobby, set an opponent slot's AI profile to `Neural`, pick a deck for each seat (the overlay's
   `decks/` has ones the model plays well) and start the game.
4. To see the seat decide, add `-Dmtgai.neural.log=true` to the launcher's Java options; each decision and a
   game-over line print to the console.

No GUI? Play one headless game against Forge's AI with the fat jar (about a minute on a laptop):

```bash
unzip lyra-model-v58625.zip -d lyra-model
M=lyra-model        # use the unpacked folder that holds latest.json; its deck folder holds the .dck files
java -Xmx3g -Xss8m -jar target/lyra-forge-plugin-0.1.0-alpha.1-all.jar --res ../forge-table/forge-gui/res \
  bench -a onnx,forge-default --model $M --vocab $M/registry.tsv \
  -d $M/2026-09-28/DECK_A.dck,$M/2026-09-28/DECK_B.dck -n 4 --seed 1
```

Replace `DECK_A.dck` and `DECK_B.dck` with any two `.dck` files from that folder. It prints a summary table with
each seat's wins. If it does, the build, the model and the engine fit together.

## Headless runner (CLI)

`mtgai.cli.Main` is the entry point of the fat jar. It needs a Forge `res/` directory: a Forge Table checkout's
`forge-gui/res`, passed with `--res`, `-Dmtgai.res` or `$MTGAI_RES`.

```bash
J="java -Xmx3g -Xss8m -jar target/lyra-forge-plugin-0.1.0-SNAPSHOT-all.jar --res ../forge-table/forge-gui/res"
$J bench -d A.dck,B.dck -a random,forge-default -n 100 --seed 1          # a seeded match, summary table
$J bench -d A.dck,B.dck -a onnx,forge-default --model MODELDIR --vocab MODELDIR/registry.tsv -n 100 --threads 8
$J play  -d A.dck,B.dck -a random,random --seed 7                        # one game: GameLog and every decision
$J validate-decks DECKDIR                                                # are these decks loadable and legal?
$J selfplay --models MODELDIR --out TRAJDIR --vocab MODELDIR/registry.tsv -d A.dck,B.dck --worker-id 0
$J replay --game GAMEID --records RECORDS.jsonl                          # re-drive a recorded game
```

Run `java -jar ...-all.jar` without arguments for the full option list. Common options: `--exposure
collapsed|full` (which priority windows the policy sees; default collapsed), `--turn-cap 60`, `--decision-cap
20000`, `--wall-ms 120000`, `-v`. `ForgeBootstrap` sets `-Dforge.mtgai.noGc=true` and
`-Dforge.mtgai.inlineAiEval=true` unless you set them.

Seat names (`-a`): `random` (uniformly random legal choices), `delegating` (Forge's AI answering every request
through the policy interface), `teacher` (Forge's AI answering the seat's own decisions; used for imitation),
`forge-default` and the other Forge AI profiles (stock `PlayerControllerAi` with game caps), `onnx` (the
policy), plus search variants (`pimc-<budget>`, `gumbel-<n>`).

## Layout

| package | what |
|---|---|
| `humanseat` | `LobbyPlayerNeural` and `NeuralSeat` (the Forge Table seat), `HumanGameLog`, update check and install helpers |
| `bootstrap` | `ForgeBootstrap`: loads Forge's static data without forge-gui |
| `decision` | `DecisionKind`, `Candidate`, `DecisionRequest` (at most 64 candidates), `DecisionResponse`, `DecisionOracle` and the simple oracles |
| `controller` | `LegalActions`, `PlayerControllerExternal` (the policy seat), `LobbyPlayerExternal`, mana and cost choices, instruments |
| `encode` | `ObservationEncoder` (encoder version 10), `Observation`, `CardVocab`, `HiddenInfo`, `ArchetypeClassifier` |
| `cardfeat` | per-card rules features for the card registry |
| `onnx` | `ModelSource` (`latest.json`, verified load), `OnnxOracle` (ONNX Runtime CPU session), `DecisionBatcher` |
| `search` | determinised search: `PimcOracle`, `GumbelOracle`, game copies and rollouts |
| `match` | `HeadlessMatch` (seeded game on its own thread, caps, crash bundle), `Watchdog`, `GameRecord` |
| `cli` | `Main` (bench / play / eval / validate-decks / vocab / selfplay / replay), `SelfPlay` |
| `traj`, `transport`, `ops` | `.mtgt` trajectory files, file and ZeroMQ sinks, metrics endpoint, game archive, decision log |

## Decision surface

What the policy decides itself, as implemented:

- PRIORITY: index 0 = PASS, then PLAY_LAND, CAST, ACTIVATE. Alternative, alternate-additional and optional
  costs (kicker, ...) are separate candidates, so enumeration never re-enters the policy. Collapsed exposure
  auto-passes UNTAP/UPKEEP/DRAW/damage steps/COMBAT_END/CLEANUP and an attacker-less DECLARE_ATTACKERS when the
  stack is empty; both modes auto-pass a window whose only legal action is PASS.
- TARGET: one pick per target, DONE (index 0) once the minimum is met; stack targets are the spell abilities on
  the stack.
- MODE, ANNOUNCE (X, bounded by the mana left), CONFIRM_ACTION, CONFIRM_TRIGGER (NO=0/YES=1), OPTIONAL_COST,
  CHOOSE_ABILITY.
- ATTACK: pick the next attacker or DONE (0). BLOCK_ATTACKER / BLOCK_BLOCKER: a two-stage loop, so double blocks
  are reachable.
- MULLIGAN (KEEP=0), MULLIGAN_BOTTOM (one card per pick), DISCARD, SCRY/SURVEIL (and their _ORDER forms).
- PLAY_EFFECT_CHOICE: which card a Play effect (cascade, ...) casts.
- Cost choices: the plain "choose N cards of a type" form of sacrifice, discard, exile, tap-a-creature and
  return-to-hand costs (COST_SACRIFICE, COST_DISCARD, COST_EXILE, COST_TAP, COST_RETURN), one card per pick.
  "Unless" costs (Mana Leak, Chain Lightning, ward) are UNLESS_COST.
- Effect choices: CHOOSE_CARD (Brainstorm put-back, tutors, fetches, Dig picks), LIBRARY_ORDER, BINARY,
  CHOOSE_TYPE, CHOOSE_COLOR.
- With `-Dmtgai.control`: combat damage assignment, trigger order, play/draw, more effect choices and mana
  payment (including floating mana).
- A request with exactly one legal candidate is answered without asking the policy.

Everything else goes to the inherited `PlayerControllerAi` with the Default profile.
