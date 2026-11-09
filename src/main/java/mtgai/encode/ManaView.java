package mtgai.encode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The seat's usable mana right now (encoder 5): its untapped mana sources and the colours each
 * can make, plus a cheap "can this mana cost be paid" check (one mana per source, the mana pool included). A source is
 * a permanent it controls with a mana ability that is activatable now ({@code canPlay}: untapped, not summoning-sick
 * for a tap cost, restrictions met) and needs no mana itself (filter lands are not counted). Read-only: it never pays,
 * taps or consumes randomness.
 *
 * <p>Colour masks use Forge's colour bits W 1, U 2, B 4, R 8, G 16, plus C 32 for colourless mana.
 */
public final class ManaView {
    public static final int C = 32;

    /** Source card -> the colours its usable mana abilities make (non-zero). Battlefield order by card id. */
    final Map<Card, Integer> sources = new IdentityHashMap<>();
    private final List<Integer> masks = new ArrayList<>();
    private final int[] pool = new int[6];

    private ManaView() {
    }

    public static ManaView of(Player p) {
        final ManaView v = new ManaView();
        final List<Card> bf = new ArrayList<>(p.getCardsIn(ZoneType.Battlefield));
        bf.sort(java.util.Comparator.comparingInt(Card::getId));
        for (Card c : bf) {
            int m = 0;
            for (SpellAbility ma : c.getManaAbilities()) {
                if (ma.getPayCosts() != null && ma.getPayCosts().hasManaCost()) {
                    continue; // needs mana itself (filter): not a source of fresh mana
                }
                if (ma.getActivatingPlayer() != p) {
                    ma.setActivatingPlayer(p);
                }
                if (ma.canPlay()) {
                    m |= colours(ma);
                }
            }
            if (m != 0) {
                v.sources.put(c, m);
                v.masks.add(m);
            }
        }
        for (int i = 0; i < 6; i++) {
            v.pool[i] = p.getManaPool().getAmountOfColor(MagicColor.WUBRGC[i]);
        }
        return v;
    }

    /** Colours one mana ability can make (W1 U2 B4 R8 G16 C32). */
    static int colours(SpellAbility ma) {
        int m = 0;
        for (int i = 0; i < 5; i++) {
            if (ma.canProduce(MagicColor.toShortString(MagicColor.WUBRG[i]))) {
                m |= 1 << i;
            }
        }
        if (ma.canProduce("C")) {
            m |= C;
        }
        return m;
    }

    /** Colours a land in hand would make once on the battlefield untapped (0 if it enters tapped or makes none). */
    public static int landColours(Card land) {
        for (ReplacementEffect re : land.getReplacementEffects()) {
            final SpellAbility o = re.getOverridingAbility();
            if (o != null && o.getApi() == ApiType.Tap && o.hasParam("ETB")) {
                return 0; // "enters tapped" (conditional ones counted as tapped: an approximation)
            }
        }
        int m = 0;
        for (SpellAbility ma : land.getManaAbilities()) {
            if (ma.getPayCosts() == null || !ma.getPayCosts().hasManaCost()) {
                m |= colours(ma);
            }
        }
        return m;
    }

    public int count() {
        return masks.size();
    }

    /** Sources that can make {@code colourBit} mana (a W1 ... C32 bit). */
    public int count(int colourBit) {
        int n = 0;
        for (int m : masks) {
            n += (m & colourBit) != 0 ? 1 : 0;
        }
        return n;
    }

    /** Colour mask of {@code c} as a usable source now, 0 if it is not one. */
    public int mask(Card c) {
        return sources.getOrDefault(c, 0);
    }

    /**
     * Can {@code cost} be paid from the pool + the sources (one mana each), plus one more source making
     * {@code extra} (0 = none)? Coloured shards are matched to sources (bipartite matching), then generic from what
     * is left. X counts 0; Phyrexian shards are assumed paid with life.
     */
    public boolean canPay(ManaCost cost, int extra) {
        if (cost == null || cost.isNoCost()) {
            return false;
        }
        final List<Integer> units = new ArrayList<>(masks);
        if (extra != 0) {
            units.add(extra);
        }
        for (int i = 0; i < 6; i++) {
            for (int k = 0; k < pool[i]; k++) {
                units.add(i < 5 ? 1 << i : C);
            }
        }
        int generic = cost.getGenericCost();
        final List<ManaCostShard> coloured = new ArrayList<>();
        for (ManaCostShard s : cost) {
            if (s.isOfKind(forge.card.mana.ManaAtom.IS_X) || s.isPhyrexian()) {
                continue;
            }
            if (s == ManaCostShard.GENERIC || s.isSnow()) {
                generic += s == ManaCostShard.GENERIC ? 0 : 1;
                continue;
            }
            coloured.add(s);
        }
        if (coloured.size() + generic > units.size()) {
            return false;
        }
        final int[] owner = new int[units.size()];
        java.util.Arrays.fill(owner, -1);
        for (int s = 0; s < coloured.size(); s++) {
            if (!augment(s, coloured, units, owner, new boolean[units.size()])) {
                return false;
            }
        }
        return units.size() - coloured.size() >= generic;
    }

    private static boolean augment(int s, List<ManaCostShard> shards, List<Integer> units, int[] owner, boolean[] seen) {
        for (int u = 0; u < units.size(); u++) {
            if (seen[u] || !payable(shards.get(s), units.get(u))) {
                continue;
            }
            seen[u] = true;
            if (owner[u] < 0 || augment(owner[u], shards, units, owner, seen)) {
                owner[u] = s;
                return true;
            }
        }
        return false;
    }

    private static boolean payable(ManaCostShard s, int mask) {
        for (int bit = 1; bit <= C; bit <<= 1) { // colour bits == forge ManaAtom W U B R G C
            if ((mask & bit) != 0 && s.canBePaidWithManaOfColor((byte) bit)) {
                return true;
            }
        }
        return false;
    }

    /** The madness cost of {@code c} (its first Madness keyword), or null if it has none. */
    public static ManaCost madnessCost(Card c) {
        if (!c.hasKeyword(Keyword.MADNESS)) {
            return null;
        }
        for (KeywordInterface k : c.getKeywords(Keyword.MADNESS)) {
            final String[] p = k.getOriginal().split(":");
            if (p.length < 2) {
                continue;
            }
            return "ManaCost".equals(p[1]) ? c.getManaCost() : new ManaCost(p[1]);
        }
        return null;
    }
}
