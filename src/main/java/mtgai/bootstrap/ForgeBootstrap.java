package mtgai.bootstrap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.card.CardType;
import forge.game.GameFormat;
import forge.game.card.CardUtil;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;

/**
 * Headless bootstrap: loads Forge's static
 * data straight from a {@code res/} directory using forge-core/forge-game/forge-ai only. No forge-gui,
 * no FModel, no GuiBase, no user profile dirs, no Sentry init. Checkstyle enforces the no-forge-gui rule.
 *
 * <p>The res dir is either a Forge checkout's {@code forge-gui/res} or a run dir's {@code res/}
 * (card scripts as {@code res/cardsfolder/cardsfolder.zip}).
 * Resolution order: explicit argument, {@code -Dmtgai.res}, {@code $MTGAI_RES}, {@code ./res}.
 *
 * <p>Also pins the engine flags every mtg-ai run uses, unless the caller set them:
 * {@code forge.mtgai.noGc=true}, {@code forge.mtgai.inlineAiEval=true} (no
 * "Game AI Eval" thread, so every controller callback runs on the game thread, where the cooperative
 * cap abort must be thrown). They are read into static finals at class load, so this must run before
 * {@code Match}/{@code AiController} are loaded.
 */
public final class ForgeBootstrap {
    private static volatile ForgeBootstrap instance;

    private final Path res;
    private final StaticData data;
    private final GameFormat.Collection formats;
    private final long loadMillis;

    private ForgeBootstrap(Path res, StaticData data, GameFormat.Collection formats, long loadMillis) {
        this.res = res;
        this.data = data;
        this.formats = formats;
        this.loadMillis = loadMillis;
    }

    public static ForgeBootstrap get() {
        return init(null);
    }

    /** Idempotent: the first call loads the card DB, later calls return the same instance. */
    public static synchronized ForgeBootstrap init(Path resDir) {
        if (instance != null) {
            return instance;
        }
        pinFlag("forge.mtgai.noGc", "true");
        pinFlag("forge.mtgai.inlineAiEval", "true");
        pinFlag("java.awt.headless", "true");

        final long t0 = System.currentTimeMillis();
        final Path res = resolveRes(resDir);
        // CardDb/StaticData print thousands of "not assigned to any set" lines to stdout; mute them unless
        // -Dmtgai.verboseBoot=true
        final java.io.PrintStream out = System.out;
        final java.io.PrintStream err = System.err;
        if (!Boolean.getBoolean("mtgai.verboseBoot")) {
            final java.io.PrintStream sink = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
            System.setOut(sink);
            System.setErr(sink);
        }
        try {
            instance = load(res, t0);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return instance;
    }

    private static ForgeBootstrap load(Path res, long t0) {
        final String sep = File.separator;
        final String r = res.toString() + sep;

        // 1. dynamic game data (FModel.loadDynamicGamedata): type lists + non-stacking keywords
        if (!CardType.Constant.LOADED.isSet()) {
            final Map<String, List<String>> contents =
                    FileSection.parseSections(FileUtil.readFile(r + "lists" + sep + "TypeLists.txt"));
            for (String section : contents.keySet()) {
                CardType.Helper.parseTypes(section, contents.get(section));
            }
            CardType.Constant.LOADED.set();
        }
        final List<String> nsk = FileUtil.readFile(r + "lists" + sep + "NonStackingKWList.txt");
        if (nsk.size() > 1) {
            for (String s : nsk) {
                if (s.length() > 1) {
                    CardUtil.NON_STACKING_LIST.add(s);
                }
            }
        }

        // 2. language: Localizer is dereferenced by Match.prepareAllZones / PlayerOutcome.toString
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", r + "languages" + sep);

        // 3. card + token scripts, editions (StaticData ctor sets the process-global instance)
        final Path emptyDir;
        try {
            emptyDir = Files.createTempDirectory("mtgai-empty");
            emptyDir.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException("cannot create temp dir", e);
        }
        // image cache dirs: CardDb.getCard consults PaperCard.hasImage; point everything at an empty dir
        final String img = emptyDir.toString() + sep;
        ImageKeys.initializeDirs(img, new HashMap<>(), img, img, img, img, img, img, img);

        final StaticData data = new StaticData(
                new CardStorageReader(r + "cardsfolder", CardStorageReader.ProgressObserver.emptyObserver, false),
                new CardStorageReader(r + "tokenscripts", CardStorageReader.ProgressObserver.emptyObserver, false),
                null,
                null,
                r + "editions",
                emptyDir.toString(),
                r + "blockdata",
                r + "setlookup",
                "",
                true,
                false,
                false,
                false,
                null);
        data.setFilteredHandsEnabled(false);
        data.setMulliganRule(forge.MulliganDefs.MulliganRule.London);

        // 4. formats (Pauper legality) and AI profiles (inherited PlayerControllerAi decisions read them)
        final GameFormat.Collection formats = new GameFormat.Collection(
                new GameFormat.Reader(new File(r + "formats"), emptyDir.toFile(), false));
        AiProfileUtil.loadAllProfiles(r + "ai" + sep);

        return new ForgeBootstrap(res, data, formats, System.currentTimeMillis() - t0);
    }

    private static void pinFlag(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    static Path resolveRes(Path explicit) {
        Path p = explicit;
        if (p == null && System.getProperty("mtgai.res") != null) {
            p = Path.of(System.getProperty("mtgai.res"));
        }
        if (p == null && System.getenv("MTGAI_RES") != null) {
            p = Path.of(System.getenv("MTGAI_RES"));
        }
        if (p == null) {
            p = Path.of("res");
        }
        p = p.toAbsolutePath().normalize();
        if (!Files.isDirectory(p.resolve("cardsfolder")) || !Files.isDirectory(p.resolve("editions"))) {
            throw new IllegalStateException("not a Forge res dir (needs cardsfolder/ and editions/): " + p
                    + " -- pass --res, -Dmtgai.res or MTGAI_RES");
        }
        return p;
    }

    public Path res() { return res; }
    public StaticData data() { return data; }
    public GameFormat.Collection formats() { return formats; }
    public GameFormat pauper() { return formats.getPauper(); }
    public long loadMillis() { return loadMillis; }
}
