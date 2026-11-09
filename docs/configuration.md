# Configuration

The seat is configured with Java system properties (`-D...`) on the game's command line, plus the model folder it
loads. There is no config file.

## The model folder

The seat loads its model once per game session from the folder named by `-Dmtgai.neural.dir` (default `neural`,
relative to Forge's working directory). The model release asset ships this folder ready to use:

```
neural/
  latest.json             {"version": 111994, "file": "policy-111994.onnx", "sha256": "...", "encoder_version": 10, "decks": [...]}
  policy-111994.onnx      the policy network (the file latest.json names)
  registry.tsv            card name -> id table for every card (preferred when present)
  cardvocab-v1.txt        the older, smaller card id list (used when registry.tsv is missing)
  2026-09-28/             the archetype deck set: the Pauper deck lists (.dck) the opponent classifier knows
  avatar.png              optional: the seat's in-game picture
```

- `decks` in `latest.json` lists the decks the model pilots best, strongest first.
- The model is checked against `sha256` when present, and its `encoder_version` must be 10, the version this build
  encodes. A model trained for another encoder version is refused.
- The deck set folder name is `ArchetypeClassifier.DECK_SET`. If it isn't next to the card id file, the seat also
  looks in `../pauper/<DECK_SET>/`.
- `avatar.png` is a Forge Table feature; the plugin doesn't read it.

## System properties

| property | what |
|---|---|
| `-Dmtgai.neural.dir=DIR` | the model folder (default `neural`) |
| `-Dmtgai.control=damage,order,playdraw,effects,mana,floatb` | the extra decision groups the policy owns; use the set the model was trained with (the one shown is Lyra's) |
| `-Dforge.mtgai.aiCastRemAIDeck=true` | Forge's AI keeps every card of its deck castable, as in training |
| `-Dmtgai.neural.greedy=false` | sample the policy instead of always taking its top choice (default greedy) |
| `-Dmtgai.neural.log=true` | print the seat's decisions (except passing priority) and a game-over line |
| `-Dmtgai.neural.games=DIR` | where per-game records go (default `userdata/mtgai-games`; empty = none) |
| `-Dforge.mtgai.neuralPlayer=CLASS` | the seat class Forge Table creates (default `mtgai.humanseat.LobbyPlayerNeural`) |

Run the game with a larger thread stack (`-Xss8m`), as Forge Table's launchers do. The headless runner has its own
options; see the [CLI guide](cli.md).
