# Build guide

## What you need

- JDK 21 and Maven 3.9 or newer.
- [Forge Table](https://github.com/mdbook/forge-table), the Forge fork that adds the hooks this plugin
  needs (the `Neural` AI profile, the `forge.mtgai.*` engine flags, and so on). Stock Forge won't load it.
- For the overlay zip: `zip`, `unzip`, `python3` and `sha256sum`.

## Build the engine, then the plugin

The plugin depends on `forge-core`, `forge-game` and `forge-ai` from Forge Table (version `2.0.15-SNAPSHOT`, set by
`forge.version` in `pom.xml`). They aren't on Maven Central, so install them into your local Maven repository first.

Forge Table's own release tag doesn't exist yet, so this pins a known-good commit instead; once a tag is cut, swap it
in for `1a127f06d02f3e8085ee4100b0b81160c8250e2e` below.

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

If the Forge Table build you install declares a different version, pass it with `-Dforge.version=<version>`. If the
Forge artifacts are missing, the build fails at dependency resolution.

## Get the model

The Lyra weights are a separate release asset, `lyra-model-v<N>.zip`, attached to the plugin release. The current
one is `lyra-model-v111994.zip` on the
[v0.1.0-alpha.1 release](https://github.com/mdbook/lyra-forge-plugin/releases/tag/v0.1.0-alpha.1). Check the zip
against the sha256 on the release page before you unpack it:

```bash
curl -LO https://github.com/mdbook/lyra-forge-plugin/releases/download/v0.1.0-alpha.1/lyra-model-v111994.zip
sha256sum lyra-model-v111994.zip   # 27f35422949fa040fb9bfeec2ec1709bed6f7e24f3001555ad3196e13be3e179
unzip lyra-model-v111994.zip
```

That gives you `neural/` (the model folder, see [configuration](configuration.md#the-model-folder)) plus
`LICENSE-MODEL.txt` and `MODEL-NOTICE.txt`. The weights are AGPL-3.0, separate from this plugin's licence.

Inside, `latest.json` carries the sha256 of the `.onnx` policy. The seat and `scripts/overlay.sh` check the model
against that hash, so a corrupt or mismatched file is rejected with a clear error. `UpdateCheck` checks a downloaded
model against the sha256 in the signed update pointer instead.

## Overlay zip for a Forge Table release

`scripts/overlay.sh` packs the plugin and a Lyra model into a zip that unpacks over a Forge Table release folder
(Forge Table's `package.sh --overlay ZIP` does that for you). Run it after `mvn package`:

```bash
scripts/overlay.sh --model lyra-model-v111994.zip [--out DIR|FILE.zip]
```

The default output is `target/lyra-forge-plugin-<version>-overlay.zip`, plus a `.sha256` next to it. `--model` takes
the model release asset as a zip or unpacked. The overlay holds:

- `lib/`: the plugin jar and ONNX Runtime, from your local Maven repo (set `MAVEN_REPO` if it isn't
  `~/.m2/repository`).
- `neural/`: the model folder, with `"name": "Lyra"` added to `latest.json` (set `LYRA_NAME` to change it).
- `decks/`: the decks `latest.json` lists.
- the licence files.

There are no Forge jars in it: the table ships those. The script stops if the model's sha256 or `encoder_version`
doesn't match this build.

## Use the seat in Forge Table

Forge Table creates the seat by class name (`mtgai.humanseat.LobbyPlayerNeural`, overridable with
`-Dforge.mtgai.neuralPlayer`) whenever a lobby slot uses the `Neural` AI profile. The plugin jar and ONNX Runtime
(`com.microsoft.onnxruntime:onnxruntime` 1.30.0) must be on the game's class path; the overlay puts both there.

1. Build the plugin and make the overlay, then unzip the overlay over a Forge Table release folder.
2. Start Forge Table with its normal launcher.
3. Open a new game lobby, set an opponent slot's AI profile to `Neural`, pick a deck for each seat (the overlay's
   `decks/` has ones the model plays well) and start the game.
4. To see the seat decide, add `-Dmtgai.neural.log=true` to the launcher's Java options; each decision and a
   game-over line print to the console.
