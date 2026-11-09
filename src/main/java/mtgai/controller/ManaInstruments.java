package mtgai.controller;

import java.util.ArrayList;
import java.util.List;

import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Mana instruments, per heuristic (AI) mana payment on an external seat: payment ambiguity, held-instant
 * regret and sac-source-cracked. A source is one unit of mana of the colours it can make (multi-mana sources and
 * cost reductions are not modelled: an approximation, good enough to say whether mana-source selection matters).
 * Held-instant regret: an instant / flash card in hand that some split of the untapped sources could pay together
 * with this cost, left unpayable by the sources the AI actually used. Rule of thumb: > 3% in any league deck
 * justifies learned mana-source selection before search.
 */
final class ManaInstruments {
    private static final String[] COLORS = {"W", "U", "B", "R", "G", "C"};
    private static final int SUBSET_CAP = 5000;

    private final Player player;
    private final List<Card> cards = new ArrayList<>();
    private final List<Integer> masks = new ArrayList<>();
    private final List<Boolean> sac = new ArrayList<>();
    private final List<Boolean> untapped = new ArrayList<>();
    private final int[] cost;
    private final List<int[]> instants = new ArrayList<>();
    private final List<String> instantNames = new ArrayList<>();
    private final String paying;

    ManaInstruments(Player player, SpellAbility sa, ManaCost toPay) {
        this.player = player;
        for (Card c : ComputerUtilMana.getAvailableManaSources(player, true)) {
            int m = 0;
            boolean s = false;
            for (SpellAbility ma : ComputerUtilMana.getAIPlayableMana(c)) {
                for (int i = 0; i < COLORS.length; i++) {
                    if (ma.getManaPart().canProduce(COLORS[i], ma)) {
                        m |= 1 << i;
                    }
                }
                s |= ComputerUtilCost.isSacrificeSelfCost(ma.getPayCosts());
            }
            if (m != 0) {
                cards.add(c);
                masks.add(m);
                sac.add(s);
                untapped.add(c.isUntapped());
            }
        }
        cost = shards(toPay);
        final Card host = sa == null ? null : sa.getHostCard();
        paying = (host == null ? "?" : host.getName()) + " " + toPay;
        for (Card c : player.getCardsIn(ZoneType.Hand)) {
            if (c != host && (c.isInstant() || c.hasKeyword(Keyword.FLASH)) && !c.isLand()) {
                instants.add(shards(c.getManaCost()));
                instantNames.add(c.getName() + " " + c.getManaCost());
            }
        }
    }

    /** After a successful payment: count into {@code s}. */
    void after(SeatStats s) {
        final int n = cards.size();
        final boolean[] used = new boolean[n];
        int nUsed = 0;
        for (int i = 0; i < n; i++) {
            final Card c = cards.get(i);
            final boolean gone = !c.isInZone(ZoneType.Battlefield) && !c.isInZone(ZoneType.Hand)
                    || c.getController() != player;
            used[i] = gone || untapped.get(i) && c.isTapped();
            nUsed += used[i] ? 1 : 0;
        }
        s.manaPayments++;
        if (n > nUsed && nUsed > 0) {
            s.manaAmbiguous++;
        }
        final int total = cost.length - 1 + cost[0];
        final List<Integer> all = new ArrayList<>(masks);
        final List<Integer> rest = new ArrayList<>();
        final List<Integer> nonSac = new ArrayList<>();
        boolean usedSac = false;
        for (int i = 0; i < n; i++) {
            if (!used[i]) {
                rest.add(masks.get(i));
            }
            if (!sac.get(i)) {
                nonSac.add(masks.get(i));
            }
            usedSac |= used[i] && sac.get(i);
        }
        if (usedSac && payable(cost, nonSac)) {
            s.manaSacCracked++;
        }
        boolean held = false;
        boolean regret = false;
        for (int k = 0; k < instants.size(); k++) {
            if (jointlyPayable(cost, instants.get(k), all, total)) {
                held = true;
                if (!payable(instants.get(k), rest)) {
                    if (!regret) {
                        final List<String> usedNames = new ArrayList<>();
                        for (int i = 0; i < n; i++) {
                            if (used[i]) {
                                usedNames.add(cards.get(i).getName());
                            }
                        }
                        s.note("held-instant regret: paid " + paying + " with " + usedNames + ", " + instantNames.get(k)
                                + " left unpayable");
                    }
                    regret = true;
                }
            }
        }
        if (held) {
            s.manaHeldInstant++;
        }
        if (regret) {
            s.manaInstantRegret++;
        }
    }

    /** {generic, shard masks...}; phyrexian shards are left out (payable with life). */
    private static int[] shards(ManaCost mc) {
        final List<Integer> out = new ArrayList<>();
        int generic = Math.max(0, mc.getGenericCost());
        for (ManaCostShard sh : mc) {
            if (sh.isPhyrexian()) {
                continue;
            }
            if (sh.isColorless()) {
                out.add(1 << 5);
            } else if (sh.isGeneric()) {
                generic++;
            } else {
                out.add((int) sh.getColorMask());
            }
        }
        final int[] r = new int[out.size() + 1];
        r[0] = generic;
        for (int i = 0; i < out.size(); i++) {
            r[i + 1] = out.get(i);
        }
        return r;
    }

    /** Colored shards matched to distinct units (backtracking), then enough units left for the generic part. */
    static boolean payable(int[] cost, List<Integer> units) {
        if (cost.length - 1 + cost[0] > units.size()) {
            return false;
        }
        return match(cost, 1, units, new boolean[units.size()]);
    }

    private static boolean match(int[] cost, int k, List<Integer> units, boolean[] taken) {
        if (k == cost.length) {
            return true; // generic: size check above covers it
        }
        for (int u = 0; u < units.size(); u++) {
            if (!taken[u] && (units.get(u) & cost[k]) != 0) {
                taken[u] = true;
                if (match(cost, k + 1, units, taken)) {
                    return true;
                }
                taken[u] = false;
            }
        }
        return false;
    }

    /** Some split of {@code units} pays {@code a} with {@code size} units and {@code b} with the rest. */
    private static boolean jointlyPayable(int[] a, int[] b, List<Integer> units, int size) {
        final int n = units.size();
        if (size > n || size + b.length - 1 + b[0] > n || !payable(b, units)) {
            return false;
        }
        final int[] idx = new int[size];
        for (int i = 0; i < size; i++) {
            idx[i] = i;
        }
        for (int tried = 0; tried < SUBSET_CAP; tried++) {
            final List<Integer> pick = new ArrayList<>();
            final List<Integer> rest = new ArrayList<>();
            int j = 0;
            for (int u = 0; u < n; u++) {
                if (j < size && idx[j] == u) {
                    pick.add(units.get(u));
                    j++;
                } else {
                    rest.add(units.get(u));
                }
            }
            if (payable(a, pick) && payable(b, rest)) {
                return true;
            }
            int i = size - 1;
            while (i >= 0 && idx[i] == n - size + i) {
                i--;
            }
            if (i < 0) {
                return false;
            }
            idx[i]++;
            for (int k = i + 1; k < size; k++) {
                idx[k] = idx[k - 1] + 1;
            }
        }
        return false;
    }
}
