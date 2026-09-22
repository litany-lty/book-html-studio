package studio.bookhtml.service;

import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Pattern STEM_BRANCH = Pattern.compile("[甲乙丙丁戊己庚辛壬癸][子丑寅卯辰巳午未申酉戌亥]");

    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile(
            "^[-—–~～·•●*#❖◆◇■□▲△▼▽|/\\\\_—=\\[\\]()（）《》【】\\s]*"
            + "(?:[0-9]{1,5}|[第]?[〇零一二三四五六七八九十百千兩两廿卅]{1,8}[页頁]?)"
            + "[-—–~～·•●*#❖◆◇■□▲△▼▽|/\\\\_—=\\[\\]()（）《》【】\\s]*$");
    private static final Pattern MARGIN_DECORATOR_PATTERN = Pattern.compile(
            "^[-—–~～·•●*#❖◆◇■□▲△▼▽★☆|/\\\\.,:;!?！？，。；：'\"“”‘’`·•_—=+§†‡\\s]+$");
    private static final Pattern FOLIO_CHAR_PATTERN = Pattern.compile(
            "^[0-9*#❖◆◇■□▲△▼▽·•~～\\-—_—=+BboO]+$");

    private ReadingStructureNormalizer() {
    }

    public static Page normalize(Page page) {
        if (page == null || page.reviewed() || page.blocks() == null || page.blocks().isEmpty()) return page;
        List<Block> rawBlocks = page.blocks();
        List<Block> normalized = new ArrayList<>(rawBlocks.size());
        boolean changed = false;
        for (Block block : rawBlocks) {
            Block next = normalize(block, rawBlocks);
            normalized.add(next);
            changed |= next != block;
        }
        List<Block> spreadOrder = verifiedVerticalSpreadOrder(page, normalized);
        if (spreadOrder != normalized) {
            normalized = new ArrayList<>(spreadOrder);
            changed = true;
        }
        if (!changed) return page;
        return new Page(page.pageNumber(), page.width(), page.height(), page.status(), page.provider(),
                List.copyOf(normalized), page.warnings(), page.reviewed(), page.error(),
                page.sourceRecords(), page.revision());
    }

    private static Block normalize(Block block, List<Block> allBlocks) {
        if (!eligible(block)) return block;
        if (isPageNumberOrFolioSymbol(block, allBlocks)) {
            return new Block(block.id(), "page-number", block.order(), block.bbox(), block.writingMode(),
                    block.original(), block.simplified(), block.confidence(), block.uncertain(), block.reviewed(),
                    null, block.source(), block.sourceIds(), block.suggestion(), block.sourceRect(),
                    block.issues());
        }
        if (relationshipDiagram(block.original()) || unreliableMatrix(block)) {
            return new Block(block.id(), "figure", block.order(), block.bbox(), block.writingMode(),
                    block.original(), block.simplified(), block.confidence(), block.uncertain(), block.reviewed(),
                    block.headingLevel(), block.source(), block.sourceIds(), block.suggestion(), block.sourceRect(),
                    block.issues());
        }
        return block;
    }

    private static boolean eligible(Block block) {
        if (block == null || block.reviewed() || !validBbox(block.bbox())) return false;
        if (!"text".equals(block.type()) && !"caption".equals(block.type())) return false;
        String source = block.source();
        return source != null && !source.isBlank()
                && !source.toLowerCase(Locale.ROOT).startsWith("manual");
    }

    private static boolean isPageNumberOrFolioSymbol(Block block, List<Block> allBlocks) {
        if (block == null || !validBbox(block.bbox())) return false;
        double[] box = block.bbox();
        double top = box[1];
        double bottom = box[1] + box[3];
        double height = box[3];
        double width = box[2];
        boolean inMargin = (top <= 0.12 || bottom <= 0.14) || (top >= 0.80 || bottom >= 0.86);
        if (!inMargin || height > 0.12) return false;
        String text = block.original();
        if (text == null || text.isBlank()) text = block.simplified();
        if (text == null || text.isBlank()) return false;
        String stripped = text.strip();
        if (stripped.length() > 24) return false;
        if (PAGE_NUMBER_PATTERN.matcher(stripped).matches() || MARGIN_DECORATOR_PATTERN.matcher(stripped).matches()) {
            return true;
        }
        if (stripped.length() <= 3 && width <= 0.08 && height <= 0.08 && FOLIO_CHAR_PATTERN.matcher(stripped).matches()) {
            if (top >= 0.84 || bottom <= 0.08) return true;
            if (hasAdjacentPageNumber(block, allBlocks, top)) return true;
        }
        return false;
    }

    private static boolean hasAdjacentPageNumber(Block block, List<Block> allBlocks, double top) {
        if (allBlocks == null) return false;
        for (Block other : allBlocks) {
            if (other == block || other == null || !validBbox(other.bbox())) continue;
            if ("page-number".equals(other.type()) || isLikelyPageNumber(other)) {
                if (Math.abs(other.bbox()[1] - top) <= 0.05) return true;
            }
        }
        return false;
    }

    private static boolean isLikelyPageNumber(Block block) {
        String text = block.original();
        if (text == null || text.isBlank()) text = block.simplified();
        if (text == null || text.isBlank()) return false;
        return PAGE_NUMBER_PATTERN.matcher(text.strip()).matches();
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

    /** Repeated short cells or flattened multi-record charts need the source crop, not a fabricated reading order. */
    static boolean unreliableMatrix(Block block) {
        if (block == null || block.original() == null || !validBbox(block.bbox())) return false;
        String[] lines = block.original().replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toArray(String[]::new);
        return repeatedCells(lines, block.bbox()) || flattenedFourColumnChart(lines, block.bbox());
    }

    private static boolean repeatedCells(String[] lines, double[] box) {
        if (lines.length < 80 || box[2] > .5 || box[3] < .2) return false;
        Map<String, Integer> frequency = new HashMap<>();
        int shortLines = 0;
        for (String line : lines) {
            if (line.codePointCount(0, line.length()) <= 4) shortLines++;
            frequency.merge(line, 1, Integer::sum);
        }
        int mostRepeated = frequency.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return shortLines >= lines.length * .8
                && frequency.size() <= Math.max(10, lines.length / 8)
                && mostRepeated >= Math.max(30, lines.length / 5);
    }

    private static boolean flattenedFourColumnChart(String[] lines, double[] box) {
        if (lines.length < 15 || lines.length > 60 || box[2] < .3 || box[3] > .4) return false;
        int records = 0;
        for (int index = 0; index < lines.length;) {
            if (index + 4 >= lines.length || !chartLabel(lines[index])) return false;
            for (int cell = 1; cell <= 4; cell++) {
                if (!STEM_BRANCH.matcher(lines[index + cell]).matches()) return false;
            }
            records++;
            index += 5;
        }
        return records >= 3;
    }

    private static boolean chartLabel(String line) {
        int length = line.codePointCount(0, line.length());
        return length >= 3 && length <= 12 && line.endsWith("造");
    }

    /** Only a fully evidenced two-page vertical spread may be regrouped; each half keeps its existing order. */
    private static List<Block> verifiedVerticalSpreadOrder(Page page, List<Block> blocks) {
        if (!Double.isFinite(page.width()) || !Double.isFinite(page.height())
                || page.height() <= 0 || page.width() < page.height() * 1.2) return blocks;
        List<Block> ordered = new ArrayList<>(blocks);
        ordered.sort(Comparator.comparingInt(Block::order));
        List<Block> right = new ArrayList<>(), left = new ArrayList<>();
        List<Integer> rightPages = new ArrayList<>();
        List<Integer> leftPages = new ArrayList<>();
        int rightVertical = 0, leftVertical = 0;
        for (Block block : ordered) {
            if (block == null || block.reviewed() || "manual".equals(block.source())
                    || !validBbox(block.bbox())) return blocks;
            double[] box = block.bbox();
            boolean onRight = box[0] >= .52;
            if (!onRight && box[0] + box[2] > .48) return blocks;
            if (onRight) right.add(block); else left.add(block);
            if ("text".equals(block.type()) && "vertical-rl".equals(block.writingMode())) {
                if (onRight) rightVertical++; else leftVertical++;
            }
            if ("page-number".equals(block.type())) {
                String text = block.original();
                if (text == null || text.isBlank()) text = block.simplified();
                if (text != null) {
                    String digits = text.replaceAll("[^0-9]", "");
                    if (digits.matches("[1-9][0-9]{0,3}")) {
                        int val = Integer.parseInt(digits);
                        if (onRight) rightPages.add(val); else leftPages.add(val);
                    }
                }
            }
        }
        if (rightVertical < 3 || leftVertical < 3) return blocks;
        Integer validRight = null, validLeft = null;
        for (int r : rightPages) {
            for (int l : leftPages) {
                if (r + 1 == l) {
                    if (validRight != null && (validRight != r || validLeft != l)) return blocks;
                    validRight = r;
                    validLeft = l;
                }
            }
        }
        if (validRight == null || validLeft == null) return blocks;
        List<Block> grouped = new ArrayList<>(ordered.size());
        grouped.addAll(right);
        grouped.addAll(left);
        boolean changed = false;
        for (int index = 0; index < grouped.size(); index++) {
            if (grouped.get(index).order() != index) { changed = true; break; }
        }
        if (!changed) return blocks;
        List<Block> resequenced = new ArrayList<>(grouped.size());
        for (int index = 0; index < grouped.size(); index++) {
            Block block = grouped.get(index);
            resequenced.add(new Block(block.id(), block.type(), index, block.bbox(), block.writingMode(),
                    block.original(), block.simplified(), block.confidence(), block.uncertain(), block.reviewed(),
                    block.headingLevel(), block.source(), block.sourceIds(), block.suggestion(), block.sourceRect(),
                    block.issues()));
        }
        return List.copyOf(resequenced);
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
