package mtgai.match;

import java.util.List;

import forge.game.Game;
import forge.game.GameState;

/**
 * A forge-game {@link GameState} (the {@code [state]} section of a Forge {@code .pzl} puzzle: zones, life,
 * active player/phase, precast / putonstack spells) applied at the start of a {@link HeadlessMatch} game,
 * after mulligans, on the game thread. Used by the behavioural fixtures ({@code .pzl} probes).
 * Player 0 of the state ("human"/p0) is seat 0, player 1 ("ai"/p1) is seat 1.
 */
public final class FixtureState extends GameState {
    public FixtureState(List<String> lines) {
        parse(lines);
    }

    /**
     * A whole Forge {@code .pzl} file (or just its {@code [state]} section): section headers and
     * {@code [metadata]} lines (no {@code =}) are ignored by the parser; blank lines are dropped.
     */
    public static FixtureState ofPzl(String text) {
        return new FixtureState(text.lines().filter(l -> !l.isBlank()).toList());
    }

    /** Applies the state synchronously on the calling (game) thread. */
    public void apply(Game game) {
        applyGameOnThread(game);
    }
}
