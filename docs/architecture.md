# Architecture

All source is in `src/main/java/mtgai/`, one package per area. Main entry points: `mtgai.humanseat.LobbyPlayerNeural`
(the seat Forge Table creates for the `Neural` AI profile), `mtgai.cli.Main` (the fat jar's main class) and
`mtgai.humanseat.UpdateCheck` (run by release launchers with only `lib/` on the class path).

## Packages

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
