package mtgai.controller;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import mtgai.decision.DecisionKind;

/** Per-seat, per-game counters (DecisionKind table + the two must-be-zero counters). */
public final class SeatStats {
    public static final int NOTE_CAP = 20;

    /** per kind: {decisions, candidate sum, max candidates, delegate misses}. */
    public final Map<DecisionKind, long[]> byKind = new EnumMap<>(DecisionKind.class);

    public long windows;            // chooseSpellAbilityToPlay calls
    public long windowsExposed;     // PRIORITY requests sent to the oracle
    public long windowsAutoPassed;  // auto-passed by the exposure filter (collapsed mode)
    public long windowsNoAction;    // enumerated, only PASS legal
    public long loopGuardBlocks;    // plays refused by the LoopGuard (no-progress repeat; stock AI passed / candidate masked)

    /** MUST be 0: a chosen PRIORITY candidate that the PlaySpellAbility pipeline refused. */
    public long activationFailures;
    /** MUST be 0: AiController.doTrigger on this seat outside AI-hint computation / delegated targeting. */
    public long aiDoTriggerOutsideTargeting;

    public long landEligibleWindows;   // own main phase, empty stack, land drop left, land in hand
    public long landMissedWindows;     // ... and no PLAY_LAND candidate offered (must be 0)
    public long landsPlayed;
    public long landTurnsEligible;     // own turns with at least one land-eligible window (target: land played on >= 95%)
    public long landTurnsPlayed;       // ... of which a land was played that turn
    public long landTurnsEligibleCompleted;  // the same, excluding the turn the game ended in
    public long landTurnsPlayedCompleted;
    public long spellsPlayed;          // PlaySpellAbility ok (non-land)
    public long triggerPlayFailures;   // PSA refused a trigger (optional trigger / no legal target: not a bug per se)
    public long candidateTruncations;  // a list was cut to the cap
    public long attackFallbacks;
    public long blockFallbacks;
    public long blockRepairs;          // invalid blocks fixed by dropping only the illegal-count blocks (no Forge fallback)
    public long targetsDelegated;      // divided-damage / random targeting left to the AI
    public long copyRetargetSkipped;   // copied spell kept its targets
    public long forcedSingle;          // requests with exactly one legal answer, answered without the oracle
    public long manaTargetRetries;     // mana payment retried allowing a targeted sac-for-mana source
    public long jointCostFiltered;     // candidates canPayCost passed but the sacrifice+mana joint check refused
    public long madnessUnpayable;      // madness cast declined without a request: its cost was not payable (encoder 5)
    public long optionalCostUnpayable; // optional trigger declined without a request: its cost was not payable
    public long teacherLabels;         // imitation teacher: requests answered with the AI's mapped choice (TEACHER steps)
    public long enumNanos;
    /** Observation encoding time (seats with an encoder). */
    public long encodeNanos;
    /** Delegated-decision share: decision callbacks answered by the inherited AI (DelegationCounter). */
    public long delegated;
    public final Map<String, Long> delegatedByMethod = new java.util.TreeMap<>();
    // mana instruments (ManaInstruments), per mana payment of this seat
    public long manaPayments;
    public long manaAmbiguous;       // more untapped sources than the payment used: the payer had a choice
    public long manaHeldInstant;     // an instant/flash card in hand was jointly payable with this cost
    public long manaInstantRegret;   // ... and the chosen sources left it unpayable (another split would not)
    public long manaSacCracked;      // a sacrifice-for-mana source was used while enough other sources were untapped
    // encoder 8 decision groups (Control)
    public long damageAssignments;   // assignCombatDamage answered through COMBAT_DAMAGE (flag damage)
    public long damageDelegated;     // ... left to the AI with the flag on: a shape the seam does not model (banding, ...)
    public long triggerOrders;       // orderSimultaneousSa answered through TRIGGER_ORDER (flag order; >= 2 distinct)
    public long manaPolicyPayments;  // mana payments answered through MANA_SOURCE (flag mana), trivial ones included
    public long manaAutoResolved;    // ... steps with one candidate after merging equivalent sources (no request)
    public long manaDelegated;       // ... payments left to the AI payer with the flag on (unsupported shape)
    /** MUST be 0: a policy mana payment that could not be completed (refunded, then the AI payer paid). */
    public long manaStranded;
    // flags float / floatb (FloatMana)
    public long floatActivations;    // floats played: float = enabler floated a source first, floatb = explicit action
    public long floatUnits;          // mana units those floats added to the pool
    public long floatNoopHidden;     // untap-enabler candidates removed as no-ops (float: and nothing to float; floatb: nothing tapped)
    public long floatManaPassed;     // pool units when the seat passed priority on an empty stack (normally lost at step end)
    public long floatWithEnabler;    // floats followed by an untap enabler in the same step (float: every successful one)
    public long floatWindows;        // floatb: priority windows that offered at least one float action
    // flag floatf (FloatMana#filterFloatActions); also counted in floatActivations / floatUnits
    public long floatFilters;        // filter floats played (Prophetic Prism, Conduit Pylons, Energy Refractor)
    /** MUST be 0: a filter float that resolved without its chosen colour in the pool. */
    public long floatFilterMissed;

    public final List<String> notes = new ArrayList<>();

    public void count(DecisionKind kind, int candidates) {
        long[] a = byKind.computeIfAbsent(kind, k -> new long[4]);
        a[0]++;
        a[1] += candidates;
        a[2] = Math.max(a[2], candidates);
    }

    public void delegateMiss(DecisionKind kind) {
        byKind.computeIfAbsent(kind, k -> new long[4])[3]++;
    }

    public void delegated(String method) {
        delegated++;
        delegatedByMethod.merge(method, 1L, Long::sum);
    }

    public void note(String s) {
        if (notes.size() < NOTE_CAP) {
            notes.add(s);
        }
    }

    public long decisions() {
        long n = 0;
        for (long[] a : byKind.values()) {
            n += a[0];
        }
        return n;
    }

    public long delegateMisses() {
        long n = 0;
        for (long[] a : byKind.values()) {
            n += a[3];
        }
        return n;
    }

    public int maxCandidates() {
        long m = 0;
        for (long[] a : byKind.values()) {
            m = Math.max(m, a[2]);
        }
        return (int) m;
    }

    /** Adds {@code o} into this (for totals over many games). */
    public void add(SeatStats o) {
        for (Map.Entry<DecisionKind, long[]> e : o.byKind.entrySet()) {
            long[] a = byKind.computeIfAbsent(e.getKey(), k -> new long[4]);
            a[0] += e.getValue()[0];
            a[1] += e.getValue()[1];
            a[2] = Math.max(a[2], e.getValue()[2]);
            a[3] += e.getValue()[3];
        }
        windows += o.windows;
        windowsExposed += o.windowsExposed;
        windowsAutoPassed += o.windowsAutoPassed;
        windowsNoAction += o.windowsNoAction;
        loopGuardBlocks += o.loopGuardBlocks;
        activationFailures += o.activationFailures;
        aiDoTriggerOutsideTargeting += o.aiDoTriggerOutsideTargeting;
        landEligibleWindows += o.landEligibleWindows;
        landMissedWindows += o.landMissedWindows;
        landsPlayed += o.landsPlayed;
        landTurnsEligible += o.landTurnsEligible;
        landTurnsPlayed += o.landTurnsPlayed;
        landTurnsEligibleCompleted += o.landTurnsEligibleCompleted;
        landTurnsPlayedCompleted += o.landTurnsPlayedCompleted;
        spellsPlayed += o.spellsPlayed;
        triggerPlayFailures += o.triggerPlayFailures;
        candidateTruncations += o.candidateTruncations;
        attackFallbacks += o.attackFallbacks;
        blockFallbacks += o.blockFallbacks;
        blockRepairs += o.blockRepairs;
        targetsDelegated += o.targetsDelegated;
        copyRetargetSkipped += o.copyRetargetSkipped;
        forcedSingle += o.forcedSingle;
        manaTargetRetries += o.manaTargetRetries;
        jointCostFiltered += o.jointCostFiltered;
        madnessUnpayable += o.madnessUnpayable;
        optionalCostUnpayable += o.optionalCostUnpayable;
        teacherLabels += o.teacherLabels;
        enumNanos += o.enumNanos;
        encodeNanos += o.encodeNanos;
        delegated += o.delegated;
        o.delegatedByMethod.forEach((k, v) -> delegatedByMethod.merge(k, v, Long::sum));
        manaPayments += o.manaPayments;
        manaAmbiguous += o.manaAmbiguous;
        manaHeldInstant += o.manaHeldInstant;
        manaInstantRegret += o.manaInstantRegret;
        manaSacCracked += o.manaSacCracked;
        damageAssignments += o.damageAssignments;
        damageDelegated += o.damageDelegated;
        triggerOrders += o.triggerOrders;
        manaPolicyPayments += o.manaPolicyPayments;
        manaAutoResolved += o.manaAutoResolved;
        manaDelegated += o.manaDelegated;
        manaStranded += o.manaStranded;
        floatActivations += o.floatActivations;
        floatUnits += o.floatUnits;
        floatNoopHidden += o.floatNoopHidden;
        floatManaPassed += o.floatManaPassed;
        floatWithEnabler += o.floatWithEnabler;
        floatWindows += o.floatWindows;
        floatFilters += o.floatFilters;
        floatFilterMissed += o.floatFilterMissed;
        for (String s : o.notes) {
            note(s);
        }
    }
}
