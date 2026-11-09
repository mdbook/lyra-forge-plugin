package mtgai.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.GameFormat;
import forge.item.PaperCard;
import mtgai.bootstrap.ForgeBootstrap;

/**
 * {@code mtgai validate-decks DIR...}: for every .dck, (1) resolve every card line's name against the card
 * DB (CardPool name resolution; unknown names would be silently dropped as "unsupported" at game start),
 * (2) check the parsed deck has as many cards as the file lists, (3) Constructed deck rules (60+/15/4),
 * (4) Forge's Pauper {@code GameFormat.isDeckLegal} with the per-card offenders. Section headers are
 * matched case-insensitively by Forge ({@code DeckSection.smartValueOf}); the report shows which spelling
 * each file uses.
 */
final class DeckValidator {
    private static final Pattern CARD = Pattern.compile("^\\s*(\\d+)\\s+(.+?)\\s*$");
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[(.+)]\\s*$");

    private DeckValidator() {
    }

    static int run(List<String> dirs, PrintStream o) throws IOException {
        final ForgeBootstrap fb = ForgeBootstrap.get();
        final GameFormat pauper = fb.pauper();
        int files = 0;
        int bad = 0;
        for (String dir : dirs) {
            final List<Path> decks;
            try (Stream<Path> s = Files.list(Path.of(dir))) {
                decks = s.filter(p -> p.toString().endsWith(".dck")).sorted().toList();
            }
            o.printf("== %s (%d decks)%n", dir, decks.size());
            for (Path p : decks) {
                files++;
                final List<String> problems = check(p, pauper, o);
                if (!problems.isEmpty()) {
                    bad++;
                    for (String pr : problems) {
                        o.println("    FAIL " + pr);
                    }
                }
            }
        }
        o.printf("validate-decks: %d files, %d with failures%n", files, bad);
        return bad == 0 ? 0 : 1;
    }

    static List<String> check(Path p, GameFormat pauper, PrintStream o) throws IOException {
        final List<String> problems = new ArrayList<>();
        final List<String> headers = new ArrayList<>();
        String section = null;
        int rawMain = 0;
        int rawSide = 0;
        for (String line : Files.readAllLines(p)) {
            final Matcher sm = SECTION.matcher(line);
            if (sm.matches()) {
                section = sm.group(1);
                headers.add("[" + section + "]");
                continue;
            }
            if (section == null || section.equalsIgnoreCase("metadata")) {
                continue;
            }
            final Matcher cm = CARD.matcher(line);
            if (!cm.matches()) {
                continue;
            }
            final int n = Integer.parseInt(cm.group(1));
            String name = cm.group(2);
            final int bar = name.indexOf('|');
            if (bar >= 0) {
                name = name.substring(0, bar);
            }
            final DeckSection ds = DeckSection.smartValueOf(section);
            if (ds == DeckSection.Main) {
                rawMain += n;
            } else if (ds == DeckSection.Sideboard) {
                rawSide += n;
            } else {
                problems.add("unknown section [" + section + "]");
            }
            final PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
            if (pc == null) {
                problems.add("card name not resolved: '" + name + "' (" + section + ")");
            } else if (pc.getRules() != null && pc.getRules().isUnsupported()) {
                problems.add("card unsupported by Forge: '" + name + "'");
            }
        }
        final Deck d = DeckSerializer.fromFile(p.toFile());
        final int main = d.getMain().countAll();
        final int side = d.has(DeckSection.Sideboard) ? d.get(DeckSection.Sideboard).countAll() : 0;
        if (main != rawMain || side != rawSide) {
            problems.add("parsed " + main + "/" + side + " cards but the file lists " + rawMain + "/" + rawSide);
        }
        final String constructed = DeckFormat.Constructed.getDeckConformanceProblem(d);
        if (constructed != null) {
            problems.add("Constructed rules: " + constructed);
        }
        final List<String> illegal = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> e : d.getAllCardsInASinglePool()) {
            if (!pauper.getFilterRules().test(e.getKey())) {
                illegal.add(e.getKey().getName());
            }
        }
        final boolean legal = pauper.isDeckLegal(d);
        if (!legal) {
            problems.add("Pauper isDeckLegal=false; illegal cards: " + illegal);
        }
        o.printf("  %-28s headers %-24s main %2d side %2d  pauper %s%s%n", p.getFileName(), String.join("", headers),
                main, side, legal ? "legal" : "ILLEGAL", problems.isEmpty() ? "  ok" : "");
        return problems;
    }
}
