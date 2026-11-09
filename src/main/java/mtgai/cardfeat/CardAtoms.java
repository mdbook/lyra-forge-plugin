package mtgai.cardfeat;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import forge.card.CardStateName;
import forge.card.CardTypeView;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.IHasSVars;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.KeywordWithCostInterface;
import forge.game.keyword.KeywordWithTypeInterface;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;

/**
 * Card content features (observation encoder 6 and later): a card's rules as a bag of
 * <em>atoms</em> read off Forge's live objects (the {@code CardFactory} output: each face's SpellAbilities with their
 * sub-ability chains, Triggers with the ability they execute, Statics with what they grant, Replacements, Keywords
 * with the traits they expand to, types, mana cost, P/T). Nothing per card is hand-authored: the only curated parts are
 * per-MECHANIC key groups (which script keys are target restrictions, magnitudes, flags, links, descriptions), the
 * same groups as the Python prototype.
 *
 * <p>An atom is a string with its context as a prefix: {@code A.} a spell or activated ability (with its sub-ability
 * chain and modes), {@code T.} a trigger's condition and {@code T.fx.} what it executes, {@code S.} a static and
 * {@code S.granted.} what it grants, {@code R.} a replacement and {@code R.fx.} its replacing ability, {@code alt.}
 * every face after the front. Presence atoms carry 1; magnitude atoms start with {@code #} and carry the number (the
 * largest |value| when a card has several).
 *
 * <p>Forge's AI hints are excluded (rules-derived atoms only): SVars are read only where a
 * rules parameter links to them (so {@code SacMe}, {@code DiscardMe}, {@code AIPreference}, {@code PlayMain1} ... never
 * are), parameters named {@code AI*} and {@code IsCurse} are skipped, and the card-level {@code AI:RemoveDeck} line is
 * not a trait.
 */
public final class CardAtoms {
    private CardAtoms() {
    }

    // ---- per-mechanic key groups (the only curated mapping; the prototype's, plus description/link keys) ----
    static final Set<String> TARGET_KEYS = Set.of("ValidTgts", "ValidCards", "ValidCard", "Defined", "Affected",
            "ValidPlayers", "ValidSA", "ValidTarget", "ValidSource", "ValidActivatingPlayer", "ChangeType", "TgtZone",
            "Origin", "Destination", "TriggerZones", "EffectZone", "ActiveZones", "ValidAttackers", "ValidBlocker");
    static final Set<String> MAGNITUDE_KEYS = Set.of("NumDmg", "NumCards", "LifeAmount", "Amount", "NumAtt", "NumDef",
            "CounterNum", "TokenAmount", "Power", "Toughness", "AddPower", "AddToughness", "TargetMax", "TargetMin",
            "Num", "ChangeNum", "Number", "LifeGain", "LifeLoss", "Produced", "RaiseCost", "ReduceCost");
    static final Set<String> FLAG_KEYS = Set.of("Tapped", "Shuffle", "OptionalDecider", "Secondary", "ETB",
            "UnlessSwitched", "Duration", "KW", "AddKeyword", "AddType", "RemoveAllAbilities", "Mode", "CounterType",
            "TokenScript", "Reveal", "Optional", "Mandatory", "Mana");
    /** Text and structure keys: descriptions, prompts, and the links the walk follows through live objects. */
    static final Set<String> SKIP_KEYS = Set.of("SpellDescription", "Description", "TriggerDescription",
            "StackDescription", "TgtPrompt", "CostDesc", "PrecostDesc", "ValidDescription", "IsCurse", "Cost",
            "UnlessCost", "SubAbility", "Execute", "ReplaceWith", "Choices", "Event", "DB", "AB", "SP",
            "ChoiceTitle", "ChoicePrompt", "AnnounceTitle", "SpellDescriptionSuffix");
    /** Static / granted-trait params naming SVars whose trait is granted (walked under {@code S.granted.}). */
    static final Set<String> GRANT_KEYS = Set.of("AddAbility", "AddTrigger", "AddStaticAbility", "AddReplacementEffect",
            "AddReplacementEffects");
    /**
     * An {@code api:Effect}'s trait-granting params -> the context letter the granted trait is read under. Forge scripts
     * "prevent all damage ... this turn" (Prismatic Strands, Hallow) as an Effect whose replacement lives in an SVar
     * ({@code ReplacementEffects$ RPrevent}); without this the extractor sees only {@code A.has:ReplacementEffects}.
     */
    static final Map<String, String> EFFECT_GRANT_KEYS = Map.of("ReplacementEffects", "R.", "StaticAbilities", "S.",
            "Triggers", "T.");
    static final int MAX_DEPTH = 8;

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern INT = Pattern.compile("-?\\d+");
    private static final Pattern TRAIT_TEXT = Pattern.compile("^\\s*(DB|AB|SP|Mode|Event)\\$.*", Pattern.DOTALL);

    /** Digits -> '#', so 'cmcLE3' and 'cmcLE2' share an atom (the number is a magnitude where it matters). */
    static String norm(String s) {
        return DIGITS.matcher(s.trim()).replaceAll("#");
    }

    static String cut(String s) {
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** Atom -> value, in order of first sight. */
    public static final class Bag {
        private final Map<String, Double> m = new LinkedHashMap<>();

        void add(String atom) {
            m.putIfAbsent(cut(atom), 1.0);
        }

        void mag(String key, double v) {
            final String k = "#" + cut(key);
            final Double o = m.get(k);
            if (o == null || Math.abs(v) > Math.abs(o)) {
                m.put(k, v);
            }
        }

        public Map<String, Double> atoms() {
            return m;
        }
    }

    /** The card's atom bag, all faces. */
    public static Bag of(Card card) {
        final Bag b = new Bag();
        final List<CardStateName> states = new ArrayList<>();
        for (CardStateName s : card.getStates()) {
            if (s != CardStateName.Original && s != CardStateName.FaceDown && !s.name().startsWith("EmptyRoom")) {
                states.add(s);
            }
        }
        states.sort(null);
        states.addFirst(CardStateName.Original);
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (CardStateName sn : states) {
            if (card.hasState(sn)) {
                face(card.getState(sn), sn == CardStateName.Original ? "" : "alt.", b, seen);
            }
        }
        return b;
    }

    static void face(CardState st, String fc, Bag b, IdentityHashMap<Object, Boolean> seen) {
        final CardTypeView ty = st.getType();
        ty.getCoreTypes().forEach(t -> b.add(fc + "type:" + t));
        ty.getSupertypes().forEach(t -> b.add(fc + "super:" + t));
        ty.getSubtypes().forEach(t -> b.add(fc + "sub:" + t));
        final ManaCost mc = st.getManaCost();
        if (mc == null || mc.isNoCost()) {
            b.add(fc + "manacost:none");
        } else {
            mana(mc, fc + "mc.", b);
            b.mag(fc + "cmc", mc.getCMC());
        }
        if (ty.isCreature()) {
            b.mag(fc + "power", st.getBasePower());
            b.mag(fc + "toughness", st.getBaseToughness());
        }
        if (ty.isPlaneswalker() && st.getBaseLoyalty() != null && INT.matcher(st.getBaseLoyalty()).matches()) {
            b.mag(fc + "loyalty", Integer.parseInt(st.getBaseLoyalty()));
        }
        if (ty.isBattle() && st.getBaseDefense() != null && INT.matcher(st.getBaseDefense()).matches()) {
            b.mag(fc + "defense", Integer.parseInt(st.getBaseDefense()));
        }
        for (KeywordInterface k : st.getCachedKeywords()) {
            keyword(k, fc, b);
        }
        for (SpellAbility sa : st.getSpellAbilities()) {
            if (!plainPermanentSpell(sa)) { // casting a permanent for its mana cost: the mc. atoms already say it
                ability(sa, fc + "A.", b, 0, seen);
            }
        }
        for (Trigger t : st.getTriggers()) {
            trigger(t, fc + "T.", b, seen);
        }
        for (StaticAbility s : st.getStaticAbilities()) {
            staticAbility(s, fc + "S.", b, seen);
        }
        for (ReplacementEffect r : st.getReplacementEffects(false)) {
            replacement(r, fc + "R.", b, seen);
        }
    }

    static boolean plainPermanentSpell(SpellAbility sa) {
        return sa.isSpell() && sa.getApi() != null && sa.getApi().name().startsWith("Permanent")
                && sa.getAlternativeCost() == null && sa.getKeyword() == null && sa.getSubAbility() == null;
    }

    static void keyword(KeywordInterface k, String fc, Bag b) {
        final String orig = k.getOriginal();
        final int colon = orig.indexOf(':');
        final String title = colon >= 0 ? orig.substring(0, colon).trim() : orig.trim();
        // Keyword.UNDEFINED is the long tail of text keywords ("CARDNAME can't block." ...): their text is the atom
        final boolean undefined = k.getKeyword() == null || "UNDEFINED".equals(k.getKeyword().name());
        final String kw = "kw:" + (undefined ? norm(title) : k.getKeyword().toString());
        b.add(fc + kw);
        if (k instanceof KeywordWithCostInterface kc) {
            Cost c = null;
            try {
                c = kc.getCost();
            } catch (RuntimeException ignored) {
                // a keyword without a parsable cost
            }
            cost(c, fc + kw + ".", "cost", b);
        }
        if (k instanceof KeywordWithTypeInterface kt && kt.getValidType() != null) {
            valid("type", kt.getValidType(), fc + kw + ".", b);
        }
        if (colon >= 0) {
            final String arg = orig.substring(colon + 1).split(":")[0].trim();
            if (INT.matcher(arg).matches()) {
                b.mag(fc + kw, Integer.parseInt(arg));
            }
        }
    }

    // ------------------------------------------------------------------ traits

    static void ability(SpellAbility sa, String ctx, Bag b, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (sa == null || depth > MAX_DEPTH || seen.put(sa, Boolean.TRUE) != null) {
            return;
        }
        if (sa.getApi() != null) {
            b.add(ctx + "api:" + sa.getApi().name());
        }
        if (sa.isManaAbility()) {
            b.add(ctx + "manaability");
        }
        if (sa.getAlternativeCost() != null) {
            b.add(ctx + "altcost:" + sa.getAlternativeCost().name());
        }
        if (!(sa instanceof AbilitySub)) {
            cost(sa.getPayCosts(), ctx, "cost", b);
        }
        if (sa.hasParam("UnlessCost")) {
            cost(parseCost(sa.getParam("UnlessCost")), ctx, "unlesscost", b);
        }
        final TargetRestrictions tr = sa.getTargetRestrictions();
        if (tr != null && !sa.hasParam("ValidTgts") && tr.getValidTgts() != null && tr.getValidTgts().length > 0) {
            valid("ValidTgts", String.join(",", tr.getValidTgts()), ctx, b);
        }
        paramMap(sa.getMapParams(), ctx, sa, b);
        if (sa.getApi() == ApiType.Effect) {
            effectGrants(sa, ctx + "granted.", b, depth + 1);
        }
        ability(sa.getSubAbility(), ctx, b, depth + 1, seen);
        for (SpellAbility x : sa.getAdditionalAbilities().values()) {
            ability(x, ctx, b, depth + 1, seen);
        }
        for (List<AbilitySub> l : sa.getAdditionalAbilityLists().values()) {
            for (AbilitySub x : l) {
                ability(x, ctx, b, depth + 1, seen);
            }
        }
    }

    static void trigger(Trigger t, String ctx, Bag b, IdentityHashMap<Object, Boolean> seen) {
        if (t == null || seen.put(t, Boolean.TRUE) != null) {
            return;
        }
        b.add(ctx + "mode:" + t.getMode().name());
        paramMap(withoutMode(t.getMapParams()), ctx, t, b);
        SpellAbility fx = null;
        try {
            fx = t.ensureAbility();
        } catch (RuntimeException ignored) {
            // an Execute that does not build outside a game: the trigger's conditions still count
        }
        ability(fx, ctx + "fx.", b, 1, seen);
    }

    static void replacement(ReplacementEffect r, String ctx, Bag b, IdentityHashMap<Object, Boolean> seen) {
        if (r == null || seen.put(r, Boolean.TRUE) != null) {
            return;
        }
        b.add(ctx + "event:" + r.getMode().name());
        paramMap(r.getMapParams(), ctx, r, b);
        SpellAbility fx = null;
        try {
            fx = r.ensureAbility();
        } catch (RuntimeException ignored) {
            // as for triggers
        }
        ability(fx, ctx + "fx.", b, 1, seen);
    }

    static void staticAbility(StaticAbility s, String ctx, Bag b, IdentityHashMap<Object, Boolean> seen) {
        if (s == null || seen.put(s, Boolean.TRUE) != null) {
            return;
        }
        for (StaticAbilityMode m : s.getMode()) {
            b.add(ctx + "mode:" + m.name());
        }
        paramMap(withoutMode(s.getMapParams()), ctx, s, b);
        if (s.hasParam("Cost")) {
            cost(parseCost(s.getParam("Cost")), ctx, "cost", b);
        }
        grants(s.getMapParams(), s, ctx + "granted.", b, 1);
    }

    /**
     * What an Effect grants for the turn: its {@code ReplacementEffects$} / {@code StaticAbilities$} / {@code Triggers$}
     * SVars, walked as for a static's grants under {@code <ctx>granted.R.} / {@code .S.} / {@code .T.} (Strands:
     * {@code A.granted.R.event:DamageDone}, {@code A.granted.R.has:Prevent}). Additive: {@code A.has:ReplacementEffects}
     * stays in the bag, so no existing atom disappears.
     */
    static void effectGrants(SpellAbility sa, String ctx, Bag b, int depth) {
        final Map<String, String> p = sa.getMapParams();
        for (Map.Entry<String, String> g : new TreeMap<>(EFFECT_GRANT_KEYS).entrySet()) {
            if (p.containsKey(g.getKey())) {
                for (String ref : p.get(g.getKey()).split("[,&]")) {
                    grantedText(ref.trim(), sa, ctx + g.getValue(), b, depth);
                }
            }
        }
    }

    static void grants(Map<String, String> p, IHasSVars holder, String ctx, Bag b, int depth) {
        for (String gk : GRANT_KEYS) {
            if (p.containsKey(gk)) {
                for (String ref : p.get(gk).split("[,&]")) {
                    grantedText(ref.trim(), holder, ctx, b, depth);
                }
            }
        }
    }

    /** A granted trait: its SVar's script text, walked through its parameter map (it has no live object here). */
    static void grantedText(String ref, IHasSVars holder, String ctx, Bag b, int depth) {
        if (ref.isEmpty() || depth > MAX_DEPTH || !holder.hasSVar(ref)) {
            return;
        }
        final String text = holder.getSVar(ref);
        if (!TRAIT_TEXT.matcher(text).matches()) {
            return;
        }
        final Map<String, String> p = AbilityFactory.getMapParams(text);
        String fxCtx = ctx;
        if (p.containsKey("Mode")) {
            b.add(ctx + "mode:" + p.get("Mode").trim());
            fxCtx = ctx + "fx.";
        } else if (p.containsKey("Event")) {
            b.add(ctx + "event:" + p.get("Event").trim());
            fxCtx = ctx + "fx.";
        } else {
            for (String k : new String[] {"DB", "AB", "SP"}) {
                if (p.containsKey(k)) {
                    b.add(ctx + "api:" + p.get(k).trim());
                }
            }
        }
        if (p.containsKey("Cost")) {
            cost(parseCost(p.get("Cost")), ctx, "cost", b);
        }
        if (p.containsKey("UnlessCost")) {
            cost(parseCost(p.get("UnlessCost")), ctx, "unlesscost", b);
        }
        paramMap(withoutMode(p), ctx, holder, b);
        for (String lk : new String[] {"SubAbility", "Execute", "ReplaceWith"}) {
            if (p.containsKey(lk)) {
                grantedText(p.get(lk).trim(), holder, "SubAbility".equals(lk) ? ctx : fxCtx, b, depth + 1);
            }
        }
        grants(p, holder, ctx, b, depth + 1);
    }

    /** A trigger's / static's {@code Mode$} is its mode atom already (not a FLAG). */
    static Map<String, String> withoutMode(Map<String, String> p) {
        final Map<String, String> q = new LinkedHashMap<>(p);
        q.remove("Mode");
        return q;
    }

    /** Text for people (descriptions, prompts, messages, titles), not rules. */
    static boolean uiText(String k) {
        return k.endsWith("Description") || k.endsWith("Desc") || k.endsWith("Message") || k.endsWith("Prompt")
                || k.endsWith("Title");
    }

    /** One trait's parameter map, key by key through the per-mechanic groups. */
    static void paramMap(Map<String, String> p, String ctx, IHasSVars svars, Bag b) {
        for (Map.Entry<String, String> e : new TreeMap<>(p).entrySet()) {
            final String k = e.getKey();
            final String v = e.getValue() == null ? "" : e.getValue().trim();
            if (SKIP_KEYS.contains(k) || GRANT_KEYS.contains(k) || k.startsWith("AI") || uiText(k)) {
                continue;
            }
            if (TARGET_KEYS.contains(k)) {
                valid(k, v, ctx, b);
            } else if (MAGNITUDE_KEYS.contains(k)) {
                if (INT.matcher(v).matches()) {
                    b.mag(ctx + k, Integer.parseInt(v));
                } else if (svars != null && svars.hasSVar(v) && !TRAIT_TEXT.matcher(svars.getSVar(v)).matches()) {
                    b.add(ctx + k + "=var:" + norm(svars.getSVar(v).split("/")[0]));
                } else {
                    b.add(ctx + k + "=" + norm(v));
                }
            } else if (FLAG_KEYS.contains(k)) {
                for (String x : v.split("[ &,]")) {
                    if (!x.isEmpty()) {
                        b.add(ctx + k + ":" + norm(x));
                    }
                }
            } else {
                b.add(ctx + "has:" + k);
            }
        }
    }

    static void valid(String key, String val, String ctx, Bag b) {
        for (String alt : val.split("[,&]")) {
            alt = alt.trim();
            if (alt.isEmpty()) {
                continue;
            }
            final String[] parts = alt.split("\\.");
            b.add(ctx + key + ":" + norm(parts[0]));
            for (int i = 1; i < parts.length; i++) {
                for (String q : parts[i].split("\\+")) {
                    if (!q.isEmpty()) {
                        b.add(ctx + key + "." + norm(q));
                    }
                }
            }
        }
    }

    static Cost parseCost(String s) {
        try {
            return new Cost(s, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Cost parts: {@code <ctx><tag>:<Part>}, {@code :<type>} per type, {@code #<ctx><tag>n:<Part>} = the amount. */
    static void cost(Cost c, String ctx, String tag, Bag b) {
        if (c == null) {
            return;
        }
        for (CostPart p : c.getCostParts()) {
            if (p instanceof CostPartMana m) {
                if (m.getMana() != null) {
                    mana(m.getMana(), ctx + ("cost".equals(tag) ? "" : tag + "."), b);
                }
                continue;
            }
            final String kind = p.getClass().getSimpleName().replaceFirst("^Cost", "");
            b.add(ctx + tag + ":" + kind);
            final String type = p.getType();
            if (type != null && !type.isEmpty()) {
                for (String t : type.split(";")) {
                    final String base = t.split("\\.")[0].trim();
                    b.add(ctx + tag + ":" + kind + ":" + ("CARDNAME".equals(base) || "Self".equals(base) ? "Self" : norm(base)));
                }
            }
            final String amt = p.getAmount();
            if (amt != null && INT.matcher(amt).matches()) {
                b.mag(ctx + tag + "n:" + kind, Integer.parseInt(amt));
            }
        }
    }

    static void mana(ManaCost mc, String ctx, Bag b) {
        for (ManaCostShard s : mc) {
            if (s != ManaCostShard.GENERIC) {
                b.add(ctx + "cost:mana:" + s.toShortString());
            }
        }
        if (mc.getGenericCost() > 0) {
            b.mag(ctx + "costn:generic", mc.getGenericCost());
        }
    }

    /** One line per card: name, then its atoms sorted ({@code #magnitude=value}), tab-separated. */
    public static String dumpLine(String name, Map<String, Double> atoms) {
        final StringBuilder sb = new StringBuilder(name);
        new TreeMap<>(atoms).forEach((k, v) -> {
            sb.append('\t').append(k);
            if (k.startsWith("#")) {
                sb.append('=').append(v == Math.rint(v) ? String.valueOf((long) (double) v) : String.valueOf(v));
            }
        });
        return sb.toString();
    }
}
