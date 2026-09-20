package studio.bookhtml.service;

import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces a read-only presentation view for OCR text that is visibly a relationship diagram.
 * The stored page and OCR transcript remain unchanged so search and later review keep their source evidence.
 */
public final class ReadingStructureNormalizer {
    private static final Pattern ARROW = Pattern.compile(
            "(?iu)(?:[\\u2190-\\u21ff\\u27f0-\\u27ff\\u2900-\\u297f]"
                    + "|\\\\(?:long)?(?:left|right|up|down|leftright|updown)?arrow\\b"
                    + "|\\\\(?:to|mapsto)\\b)");

    private ReadingStructureNormalizer() {
    }

    public static Page normalize(Page page) {
        if (page == null || page.reviewed() || page.blocks() == null || page.blocks().isEmpty()) return page;
        List<Block> normalized = new ArrayList<>(page.blocks().size());
        boolean changed = false;
        for (Block block : page.blocks()) {
            Block next = normalize(block);
            normalized.add(next);
            changed |= next != block;
        }
        if (!changed) return page;
        return new Page(page.pageNumber(), page.width(), page.height(), page.status(), page.provider(),
                List.copyOf(normalized), page.warnings(), page.reviewed(), page.error(),
                page.sourceRecords(), page.revision());
    }

    private static Block normalize(Block block) {
        if (!eligible(block) || !relationshipDiagram(block.original())) return block;
        return new Block(block.id(), "figure", block.order(), block.bbox(), block.writingMode(),
                block.original(), block.simplified(), block.confidence(), block.uncertain(), block.reviewed(),
                block.headingLevel(), block.source(), block.sourceIds(), block.suggestion(), block.sourceRect(),
                block.issues());
    }

    private static boolean eligible(Block block) {
        if (block == null || !"text".equals(block.type()) || block.reviewed() || !validBbox(block.bbox())) return false;
        String source = block.source();
        return source != null && !source.isBlank()
                && !source.toLowerCase(Locale.ROOT).startsWith("manual");
    }

    static boolean relationshipDiagram(String value) {
        if (value == null) return false;
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toArray(String[]::new);
        if (lines.length < 3) return false;

        Matcher arrows = ARROW.matcher(value);
        int arrowCount = 0;
        while (arrows.find() && arrowCount < 4) arrowCount++;
        if (arrowCount >= 4) return true;

        if (lines.length < 4) return false;
        int connectorLines = 0;
        for (String line : lines) {
            if (verticalConnectorLine(line) && ++connectorLines >= 2) return true;
        }
        return false;
    }

    private static boolean verticalConnectorLine(String line) {
        int connectors = 0;
        int nonConnectors = 0;
        int run = 0;
        int longestRun = 0;
        for (int offset = 0; offset < line.length();) {
            int codePoint = line.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) continue;
            if (isVerticalConnector(codePoint)) {
                connectors++;
                longestRun = Math.max(longestRun, ++run);
            } else {
                nonConnectors++;
                run = 0;
            }
        }
        return connectors >= 3 && longestRun >= 3 && nonConnectors <= 2;
    }

    private static boolean isVerticalConnector(int codePoint) {
        return codePoint == '|' || codePoint == '\uff5c' || codePoint == '\u00a6' || codePoint == '\u4e28'
                || codePoint == '\u2502' || codePoint == '\u2503' || codePoint == '\u2506'
                || codePoint == '\u2507' || codePoint == '\u250a' || codePoint == '\u250b'
                || codePoint == '\u254e' || codePoint == '\u254f';
    }

    private static boolean validBbox(double[] bbox) {
        if (bbox == null || bbox.length != 4) return false;
        for (double value : bbox) if (!Double.isFinite(value)) return false;
        return bbox[0] >= 0 && bbox[1] >= 0 && bbox[2] > 0 && bbox[3] > 0
                && bbox[0] + bbox[2] <= 1.000001 && bbox[1] + bbox[3] <= 1.000001;
    }
}
