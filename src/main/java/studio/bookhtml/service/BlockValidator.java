package studio.bookhtml.service;

import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import java.util.*;

public final class BlockValidator {
    private static final Set<String> TYPES = Set.of("text", "heading", "figure", "table", "caption", "page-number", "formula");
    private static final Set<String> MODES = Set.of("vertical-rl", "horizontal-tb");
    private BlockValidator() {}
    public static void validate(List<Block> blocks) {
        if (blocks == null || blocks.size() > 10000) throw bad();
        Set<String> ids = new HashSet<>(); Set<Integer> orders = new HashSet<>();
        for (Block b : blocks) {
            if (b == null || b.id() == null || b.id().isBlank() || b.id().length() > 120 || !ids.add(b.id()) || !orders.add(b.order())) throw bad();
            if (!TYPES.contains(b.type()) || !MODES.contains(b.writingMode()) || b.order() < 0) throw bad();
            validateBbox(b.bbox());
            if (b.confidence() != null && (!Double.isFinite(b.confidence()) || b.confidence() < 0 || b.confidence() > 1)) throw bad();
            if (b.headingLevel() != null && (b.headingLevel() < 1 || b.headingLevel() > 6)) throw bad();
            if ((b.original() != null && b.original().length() > 1_000_000) || (b.simplified() != null && b.simplified().length() > 1_000_000)) throw bad();
            validateIssues(b);
        }
    }
    private static void validateIssues(Block block){List<ContentIssue>issues=block.issues();if(issues==null||issues.size()>10_000)throw bad();String original=block.original()==null?"":block.original(),simplified=block.simplified()==null?"":block.simplified();Set<String>ids=new HashSet<>();int previousEnd=-1;List<ContentIssue>ordered=new ArrayList<>(issues);ordered.sort(Comparator.comparingInt(ContentIssue::start).thenComparingInt(ContentIssue::end));for(ContentIssue issue:ordered){if(issue==null||issue.id()==null||issue.id().isBlank()||issue.id().length()>120||!ids.add(issue.id())||!Set.of("unreadable","suspected").contains(issue.kind()))throw bad();if(issue.start()<0||issue.end()<=issue.start()||issue.end()>original.length()||issue.start()<previousEnd)throw bad();if(issue.simplifiedStart()<0||issue.simplifiedEnd()<issue.simplifiedStart()||issue.simplifiedEnd()>simplified.length())throw bad();if(length(issue.reason())>1_000||length(issue.replacement())>1_000||length(issue.inferredText())>1_000)throw bad();if(issue.resolved()&&issue.replacement()==null)throw bad();previousEnd=issue.end();}}
    private static int length(String value){return value==null?0:value.length();}
    public static void validateBbox(double[] bbox) {
        if (bbox == null || bbox.length != 4) throw bad();
        for (double d : bbox) if (!Double.isFinite(d) || d < 0 || d > 1) throw bad();
        if (bbox[2] <= 0 || bbox[3] <= 0 || bbox[0] + bbox[2] > 1.000001 || bbox[1] + bbox[3] > 1.000001) throw bad();
    }
    private static ApiException bad() { return new ApiException(HttpStatus.BAD_REQUEST, "页面块数据或坐标无效"); }
}
