package mtgai.encode;

/**
 * One encoded decision: the token sections as packed byte rows plus the 32 global
 * bytes. Counts are {@code section.length / token size}. {@code orc} / {@code orcFlags} are the schema-2 ORACLE
 * section: training-only, never an actor input.
 */
public record Observation(int kind, byte[] obj, byte[] lib, byte[] opp, byte[] hist, byte[] cand, byte[] glob,
                          byte[] orc, int orcFlags) {
    /** No ORACLE section. */
    public Observation(int kind, byte[] obj, byte[] lib, byte[] opp, byte[] hist, byte[] cand, byte[] glob) {
        this(kind, obj, lib, opp, hist, cand, glob, new byte[0], 0);
    }

    public static final int OBJ = 48;
    public static final int HIST = 12;
    public static final int CAND = 24;
    public static final int GLOBAL = 32;

    public int nObj() {
        return obj.length / OBJ;
    }

    public int nLib() {
        return lib.length / OBJ;
    }

    public int nOpp() {
        return opp.length / OBJ;
    }

    public int nHist() {
        return hist.length / HIST;
    }

    /**
     * The HISTORY the acting policy sees: the last {@link ObservationEncoder#MAX_HIST} tokens. Equal to {@link #hist}
     * unless the encoder records more ({@code mtgai.histRecord}, offline experiments only).
     */
    public byte[] actingHist() {
        final int n = ObservationEncoder.MAX_HIST * HIST;
        return hist.length <= n ? hist : java.util.Arrays.copyOfRange(hist, hist.length - n, hist.length);
    }

    public int nOrc() {
        return orc.length / OBJ;
    }

    public int nCand() {
        return cand.length / CAND;
    }
}
