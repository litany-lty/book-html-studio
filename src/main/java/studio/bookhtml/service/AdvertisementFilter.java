package studio.bookhtml.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;

/** Marks only isolated, high-confidence margin promotions; never removes OCR evidence. */
public final class AdvertisementFilter {
    private static final Set<String> TEXT_TYPES = Set.of("text", "heading", "caption");
    private static final Pattern PROMOTION = Pattern.compile(
            "更多.{0,6}(?:低价|资料|优惠)|低价.{0,4}(?:资料|书|教材)|购买|购书|买书|订购|优惠资料|获取资料|加微信|扫码购买");
    private static final Pattern CONTACT_ID = Pattern.compile(
            "(?:微信|weixin|wechat|vx|qq)(?:号|账号|联系)?[^\\p{L}\\p{N}]{0,6}"
                    + "(?:[a-z][a-z0-9_-]{2,31}|[0-9]{5,16})");
    private static final Pattern BODY_SENTENCE = Pattern.compile("[。！？；.!?;]");

    private AdvertisementFilter() {}

    public record Result(List<Block> blocks, int marked, int heldForReview) {}

    public static Result classify(List<Block> blocks, boolean pageReviewed, TraditionalConverter converter) {
        if (blocks == null || blocks.isEmpty() || pageReviewed) return new Result(blocks, 0, 0);
        List<Block> result = new ArrayList<>(blocks.size());
        int marked = 0, held = 0;
        for (Block block : blocks) {
            if (!marginText(block) || !advertisingSignal(block, converter)) {
                result.add(block);
                continue;
            }
            // Manual decisions and confirmed issue resolutions stay visible; unresolved issues remain on the marked block.
            if (block.reviewed() || "manual".equals(block.source())) {
                result.add(block);
            } else if (block.issues() != null && block.issues().stream().anyMatch(ContentIssue::resolved)) {
                result.add(block);
                held++;
            } else {
                result.add(new Block(block.id(), "advertisement", block.order(), block.bbox(),
                        block.writingMode(), block.original(), block.simplified(), block.confidence(),
                        block.uncertain(), block.reviewed(), block.headingLevel(), block.source(),
                        block.sourceIds(), block.suggestion(), block.sourceRect(), block.issues()));
                marked++;
            }
        }
        return new Result(marked == 0 ? blocks : List.copyOf(result), marked, held);
    }

    private static boolean marginText(Block block) {
        if (block == null || block.type() == null || !TEXT_TYPES.contains(block.type()) || block.bbox() == null
                || block.bbox().length != 4) return false;
        double[] box = block.bbox();
        if (!Double.isFinite(box[1]) || !Double.isFinite(box[3]) || box[3] <= 0 || box[3] > .06) return false;
        return box[1] >= 0 && (box[1] + box[3] <= .10 || box[1] >= .90);
    }

    private static boolean advertisingSignal(Block block, TraditionalConverter converter) {
        String text = block.simplified() == null || block.simplified().isBlank()
                ? block.original() : block.simplified();
        if (text == null) return false;
        String normalized = Normalizer.normalize(converter.toSimplified(text), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{Z}\\s]", "");
        int length = normalized.codePointCount(0, normalized.length());
        return length >= 8 && length <= 60 && !BODY_SENTENCE.matcher(normalized).find()
                && PROMOTION.matcher(normalized).find() && CONTACT_ID.matcher(normalized).find();
    }
}
