package mtgai.controller;

import java.util.List;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import mtgai.match.Watchdog;

/**
 * The stock Forge AI ("forge-default" agent), unchanged except that the per-game {@link Watchdog} caps are
 * checked at its priority / attack / block calls, so a stock-vs-stock game is capped like any other, and that a
 * handicapped start ({@link mtgai.match.Handicap} MULL6 / DRAW, {@link LobbyPlayerExternal}) is honoured, and that a
 * no-progress loop is broken by passing priority ({@link LoopGuard}).
 */
public class WatchedAiController extends PlayerControllerAi {
    private final Watchdog watchdog;
    private final SeatStats stats;
    private final LoopGuard loopGuard = new LoopGuard();

    public WatchedAiController(Game game, Player p, LobbyPlayer lp, Watchdog watchdog, SeatStats stats) {
        super(game, p, lp);
        this.watchdog = watchdog;
        this.stats = stats;
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return LobbyPlayerExternal.startingPlayer(getGame(), super.chooseStartingPlayer(isFirstGame)); // Handicap.DRAW
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        final Boolean forced = LobbyPlayerExternal.forcedKeep(player); // Handicap.MULL6
        return forced != null ? forced : super.mulliganKeepHand(firstPlayer, cardsToReturn);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        watchdog.onDecision(getGame());
        final List<SpellAbility> ai = super.chooseSpellAbilityToPlay();
        if (ai == null || ai.isEmpty() || ai.get(0).isLandAbility()) {
            return ai;
        }
        if (loopGuard.blocked(getGame(), ai.get(0))) {
            stats.loopGuardBlocks++;
            return null; // pass priority
        }
        loopGuard.played(getGame(), ai.get(0));
        return ai;
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        watchdog.onDecision(getGame());
        super.declareAttackers(attacker, combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        watchdog.onDecision(getGame());
        super.declareBlockers(defender, combat);
    }
}
