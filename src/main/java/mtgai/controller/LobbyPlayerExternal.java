package mtgai.controller;

import java.util.Map;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.zone.ZoneType;
import mtgai.decision.DecisionOracle;
import mtgai.match.GameRecord;
import mtgai.match.Handicap;
import mtgai.match.Watchdog;

/**
 * Attaches a {@link PlayerControllerExternal} (oracle != null) or a {@link WatchedAiController} (oracle ==
 * null: the stock "forge-default" AI) to the in-game player. Extends LobbyPlayerAi so the inherited AI
 * code finds its profile: "Default", or for a stock-AI seat named {@code forge-cautious} / {@code forge-reckless} /
 * {@code forge-experimental} the matching Forge personality (res/ai/*.ai, {@link #FORGE_PROFILES}). One instance per
 * seat per game.
 */
public class LobbyPlayerExternal extends LobbyPlayerAi {
    /** Stock-AI agent name -> Forge AI profile (res/ai/NAME.ai). Anything else plays the "Default" profile. */
    public static final Map<String, String> FORGE_PROFILES = Map.of(
            "forge-default", "Default",
            "forge-cautious", "Cautious",
            "forge-reckless", "Reckless",
            "forge-experimental", "Experimental");

    /**
     * The Forge AI profile of agent {@code agent}: its {@link #FORGE_PROFILES} entry, else "Default". A stock-AI seat
     * piloting another deck ({@code forge-cautious@elves}) plays its base agent's profile.
     */
    public static String profileFor(String agent) {
        return FORGE_PROFILES.getOrDefault(baseAgent(agent), "Default");
    }

    /**
     * {@code agent} without an {@code @<deck>} suffix: {@code forge-default@blue-terror} -> {@code forge-default} (the
     * stock AI piloting the blue-terror deck); any other name unchanged.
     */
    public static String baseAgent(String agent) {
        final int at = agent.indexOf('@');
        return at < 0 ? agent : agent.substring(0, at);
    }

    /** True for a stock Forge AI agent name (no oracle): forge-default or one of the other personalities. */
    public static boolean isForgeAgent(String agent) {
        return FORGE_PROFILES.containsKey(agent);
    }

    /** This seat's handicapped start this game (null = none); set by HeadlessMatch before the game starts. */
    private Handicap handicap;

    public void handicap(Handicap h) {
        this.handicap = h;
    }

    public Handicap handicap() {
        return handicap;
    }

    /** The handicap of in-game player {@code p} (null = none, or not a seat of ours). */
    public static Handicap handicapOf(Player p) {
        return p.getLobbyPlayer() instanceof LobbyPlayerExternal l ? l.handicap : null;
    }

    /**
     * {@code chooseStartingPlayer} for every controller of ours: a {@link Handicap#DRAW} seat is on the draw whoever
     * won the coin toss (its opponent plays first); otherwise {@code chosen} (the controller's own answer).
     */
    public static Player startingPlayer(Game game, Player chosen) {
        for (Player p : game.getPlayers()) {
            if (handicapOf(p) == Handicap.DRAW) {
                for (Player o : game.getPlayers()) {
                    if (o != p) {
                        return o;
                    }
                }
            }
        }
        return chosen;
    }

    /**
     * {@code mulliganKeepHand} forced by a {@link Handicap#MULL6} seat: keep once the hand is down to
     * {@link Handicap#MULL_TO} cards (London: the bottom card was already chosen), mulligan before; null = not forced.
     */
    public static Boolean forcedKeep(Player p) {
        if (handicapOf(p) != Handicap.MULL6) {
            return null;
        }
        return p.getCardsIn(ZoneType.Hand).size() <= Handicap.MULL_TO;
    }

    private final DecisionOracle oracle;
    private final int seat;
    private final PlayerControllerExternal.Exposure exposure;
    private final Watchdog watchdog;
    private final GameRecord record;
    private final SeatStats stats;
    private PlayerController lastController;
    /** Encoder 8 decision groups for an external seat (set by HeadlessMatch before the game starts). */
    private Control control = Control.NONE;

    public void control(Control c) {
        this.control = c == null ? Control.NONE : c;
    }

    public LobbyPlayerExternal(String name, DecisionOracle oracle, int seat, PlayerControllerExternal.Exposure exposure,
                               Watchdog watchdog, GameRecord record, SeatStats stats) {
        this(name, oracle, seat, exposure, watchdog, record, stats, "Default");
    }

    public LobbyPlayerExternal(String name, DecisionOracle oracle, int seat, PlayerControllerExternal.Exposure exposure,
                               Watchdog watchdog, GameRecord record, SeatStats stats, String aiProfile) {
        super(name, null);
        setAiProfile(aiProfile);
        this.oracle = oracle;
        this.seat = seat;
        this.exposure = exposure;
        this.watchdog = watchdog;
        this.record = record;
        this.stats = stats;
    }

    private PlayerController create(Player p) {
        lastController = oracle == null
                ? new WatchedAiController(p.getGame(), p, this, watchdog, stats)
                : new PlayerControllerExternal(p.getGame(), p, this, oracle, seat, exposure, watchdog, record, stats, control);
        return lastController;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        // control-magic of a player is out of scope for Pauper v1: the stock AI drives a mind-slaved player
        return new WatchedAiController(slave.getGame(), slave, this, watchdog, stats);
    }

    /**
     * Controller injection on game copies (used by PIMC): while set on the copying thread, a game copy
     * ({@code GameCopier} keeps this lobby player for the copy's seats) gets its controllers from this factory instead
     * of {@link #create}, so a rollout never reaches the live seat's oracle, record or stats.
     */
    public static final ThreadLocal<CopyControllers> COPY_CONTROLLERS = new ThreadLocal<>();

    /** Builds the controller of seat {@code seat}'s player on a game copy. */
    public interface CopyControllers {
        PlayerController create(Player copyPlayer, LobbyPlayerExternal lobby, int seat);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        final Player p = new Player(getName(), game, id);
        final CopyControllers copy = COPY_CONTROLLERS.get();
        p.setFirstController(copy != null ? copy.create(p, this, seat) : create(p));
        return p;
    }

    public PlayerController lastController() {
        return lastController;
    }

    public int seat() {
        return seat;
    }

    public DecisionOracle oracle() {
        return oracle;
    }
}
