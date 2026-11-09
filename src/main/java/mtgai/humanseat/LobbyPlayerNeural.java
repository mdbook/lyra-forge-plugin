package mtgai.humanseat;

import java.util.Set;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * Human seat: the lobby player behind the fork's {@code Neural} AI profile. Forge's
 * {@code GamePlayerUtil.createAiPlayer} builds it by name ({@code forge.mtgai.neuralPlayer}) with this
 * (String, Set&lt;AIOption&gt;) constructor, so the desktop GUI seats it wherever it would seat a Forge AI.
 * The model loads in the constructor, so a missing model fails in the lobby (Forge falls back to the Default AI and
 * says why), not mid-game. Every decision the policy does not own goes to the inherited Forge AI with the Default
 * profile, as in self-play.
 */
public class LobbyPlayerNeural extends LobbyPlayerAi {
    public LobbyPlayerNeural(String name, Set<AIOption> options) {
        super(name, null); // no simulation AI option: the fallback AI is the plain Default AI, as in self-play
        setAiProfile("Default");
        NeuralSeat.get();
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        final Player p = new Player(getName(), game, id);
        p.setFirstController(NeuralSeat.get().controller(game, p, this, id == 0 ? 0 : 1));
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new PlayerControllerAi(slave.getGame(), slave, this); // as in self-play: control magic = stock AI
    }
}
