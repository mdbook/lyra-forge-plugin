# CLI guide

`mtgai.cli.Main` is the entry point of the fat jar (`target/lyra-forge-plugin-<version>-all.jar`, see the
[build guide](build.md)). It needs a Forge `res/` directory: a Forge Table checkout's `forge-gui/res`, passed with
`--res`, `-Dmtgai.res` or `$MTGAI_RES`.

```bash
J="java -Xmx3g -Xss8m -jar target/lyra-forge-plugin-<version>-all.jar --res ../forge-table/forge-gui/res"
$J bench -d A.dck,B.dck -a random,forge-default -n 100 --seed 1          # a seeded match, summary table
$J bench -d A.dck,B.dck -a onnx,forge-default --model neural --vocab neural/registry.tsv -n 100 --threads 8
$J play  -d A.dck,B.dck -a random,random --seed 7                        # one game: GameLog and every decision
$J validate-decks DECKDIR                                                # are these decks loadable and legal?
$J selfplay --models MODELDIR --out TRAJDIR --vocab MODELDIR/registry.tsv -d A.dck,B.dck --worker-id 0
$J replay --game GAMEID --records RECORDS.jsonl                          # re-drive a recorded game
```

The other commands are `eval`, `vocab` and `cardfeatures`. Run `java -jar ...-all.jar` without arguments for the full
option list. Common options: `--exposure collapsed|full` (which priority windows the policy sees; default
collapsed), `--turn-cap 60`, `--decision-cap 20000`, `--wall-ms 120000`, `-v`. `ForgeBootstrap` sets
`-Dforge.mtgai.noGc=true` and `-Dforge.mtgai.inlineAiEval=true` unless you set them.

## Seat names (`-a`)

- `random`: uniformly random legal choices.
- `delegating`: Forge's AI answering every request through the policy interface.
- `teacher`: Forge's AI answering the seat's own decisions; used for imitation.
- `forge-default` and the other Forge AI profiles (`forge-cautious`, `forge-reckless`, `forge-experimental`): stock
  `PlayerControllerAi` with game caps.
- `onnx`: the policy. Needs `--model DIR --vocab FILE`.
- Search variants: `pimc-<budget>`, `gumbel-<n>`.
