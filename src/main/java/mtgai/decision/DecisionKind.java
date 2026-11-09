package mtgai.decision;

/**
 * Every decision the external seat exposes to a {@link DecisionOracle}. One request = one
 * pick of one index from a short legal list; compound choices are sequences of requests.
 */
public enum DecisionKind {
    /** Keep (0) or mulligan (1) the current opening hand. */
    MULLIGAN,
    /** London mulligan: pick one card to put on the bottom; repeated N times. */
    MULLIGAN_BOTTOM,
    /** Priority: PASS (index 0), PLAY_LAND, CAST, ACTIVATE; kicker/alt-cost variants are distinct candidates. */
    PRIORITY,
    /** One target pick for one targeting node ({@code chooseTargetsFor}); DONE once the minimum is met. */
    TARGET,
    /** One mode pick for a modal spell/ability; DONE once the minimum is met. */
    MODE,
    /** An announced value: X, or another {@code Announce$} variable; candidates are the affordable values. */
    ANNOUNCE,
    /** A "you may" confirmation ({@code confirmAction}): NO (0) / YES (1). */
    CONFIRM_ACTION,
    /** An optional trigger ({@code confirmTrigger}): NO (0) / YES (1). */
    CONFIRM_TRIGGER,
    /** Optional costs (kicker, ...) asked outside priority enumeration, e.g. a spell cast by an effect. */
    OPTIONAL_COST,
    /** Which of a card's abilities / alternative-additional-cost variants, asked outside priority enumeration. */
    CHOOSE_ABILITY,
    /** Which card to play from a Play effect (cascade, ...); DONE (index 0) = none, when optional. */
    PLAY_EFFECT_CHOICE,
    /** Declare attackers: pick the next attacker or DONE (index 0). */
    ATTACK,
    /** Declare blockers stage 1: pick an attacker to (further) block, or DONE (index 0). */
    BLOCK_ATTACKER,
    /** Declare blockers stage 2: pick the blocker for the attacker picked in stage 1. */
    BLOCK_BLOCKER,
    /** Discard: pick the next card to discard; DONE once the minimum is met (when optional). */
    DISCARD,
    /** Scry: per card, TOP (0) or BOTTOM (1). */
    SCRY,
    /** Scry: order the cards kept on top, picking the next card from the top. */
    SCRY_ORDER,
    /** Surveil: per card, TOP (0) or GRAVEYARD (1). */
    SURVEIL,
    /** Surveil: order the cards kept on top. */
    SURVEIL_ORDER,

    // appended later (encoder_version 2): ordinals are wire format, append only

    /**
     * A card (or player) for an effect: hidden-origin zone changes (Brainstorm put-back, Kor Skyfisher, tutors,
     * fetches), Dig's pick (Malevolent Rumble), other single-entity choices; DONE (index 0) when optional.
     */
    CHOOSE_CARD,
    /** Cards going onto the top of a library (Brainstorm put-back, Ponder): pick the next card from the top. */
    LIBRARY_ORDER,
    /** A binary effect choice ({@code chooseBinary}, e.g. tap or untap): NO = false (0) / YES = true (1). */
    BINARY,
    /** An "unless" cost ({@code payCostToPreventEffect}): NO = don't pay (0) / YES = pay (1). */
    UNLESS_COST,
    /** The next permanent to sacrifice: sacrifice costs and edicts. */
    COST_SACRIFICE,
    /** Cost choice: the next card to discard. */
    COST_DISCARD,
    /** Cost choice: the next card to exile. */
    COST_EXILE,
    /** Cost choice: the next permanent to tap ("tap an untapped creature you control"). */
    COST_TAP,
    /** Cost choice: the next permanent to return to hand (ninjutsu's unblocked attacker). */
    COST_RETURN,
    /** "Choose a type" (Winding Way: creature or land). */
    CHOOSE_TYPE,
    /** "Choose a color" (Prismatic Strands, Utopia Sprawl): one color per pick; DONE once the minimum is met. */
    CHOOSE_COLOR,

    // appended later (encoder_version 8); each behind a Control flag

    /**
     * Combat damage assignment ({@code assignCombatDamage}, flag {@code damage}): the recipient of the next chunk of an
     * attacker's (or a multi-blocking blocker's) damage: a creature in the damage assignment (CARD; the chunk is what it
     * still needs for lethal, or all that is left once it has lethal) or, with trample once every blocker has lethal,
     * the defender (PLAYER, or CARD for a planeswalker / battle: all that is left). Candidate bytes 13-15 (encoder 8).
     */
    COMBAT_DAMAGE,
    /** Simultaneous triggers ({@code orderSimultaneousSa}, flag {@code order}): the one that resolves next (ABILITY). */
    TRIGGER_ORDER,
    /**
     * Mana payment ({@code payManaCost}, flag {@code mana}): the source (and colour) that pays the next part of a mana
     * cost; every candidate keeps the rest of the cost payable. Candidate bytes 13-18 (encoder 8).
     */
    MANA_SOURCE,
    /** Play or draw ({@code chooseStartingPlayer}, flag {@code playdraw}): NO = draw (0) / YES = play (1). */
    PLAY_DRAW;
}
