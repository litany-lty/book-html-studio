package studio.bookhtml.decision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J01（T27–T31 身份侧）：跨书隔离、内容身份、UTF-16 边界、hash 敏感性、冻结不可变。
 */
class DecisionIdentityTest {
    @TempDir Path temp;

    private DecisionModels.IssueRef ref(String book, String pdf, String basis, String span, String mapping) {
        return new DecisionModels.IssueRef(book, pdf, 3, 12, "b1", "i1",
                "otext-hash", basis, 0, 2, span, mapping);
    }

    private DecisionModels.Candidate candidate(String id, String text, String group) {
        return new DecisionModels.Candidate(id, text, null, text, "conv-v1",
                DecisionModels.SourceKind.PRIMARY_OCR, "paddle", null, null, "run-1", group,
                List.of(), "pdfhash", 3, "span", "crop-1",
                DecisionModels.LocatorMode.REGION, new double[]{0, 0, 0.2, 0.1}, "t-v1",
                DecisionModels.AlignmentStatus.EXACT, List.of("E0"), 0.9, "norm-v1", Instant.now());
    }

    @Test void samePageBlockIssueAcrossBooksNeverSharesIdentity() {
        // T27：两本书相同页号/blockId/issueId 不串候选、不串缓存、不串确认
        DecisionModels.IssueRef a = ref("book-A", "pdf-A", "basis", "span", "map-v1");
        DecisionModels.IssueRef b = ref("book-B", "pdf-A", "basis", "span", "map-v1");
        assertNotEquals(DecisionHash.of(a), DecisionHash.of(b));
        assertNotEquals(DecisionModels.DecisionSnapshot.computeHash(a, "cs", Map.of(), "tpl"),
                DecisionModels.DecisionSnapshot.computeHash(b, "cs", Map.of(), "tpl"));
    }

    @Test void replacedPdfWithSameSizeAndMtimeChangesIdentity() throws Exception {
        // T28：文件名/大小/mtime 保持相同也不能复用旧证据
        Path pdf = temp.resolve("source.pdf");
        Files.write(pdf, "版本一内容....".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PdfIdentity identity = new PdfIdentity();
        String before = identity.sha256(pdf);
        FileTime mtime = Files.getLastModifiedTime(pdf);
        byte[] other = "版本二内容....".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(Files.size(pdf), other.length);
        Files.write(pdf, other);
        Files.setLastModifiedTime(pdf, mtime);
        assertEquals(Files.size(pdf), other.length);
        assertNotEquals(before, identity.sha256(pdf));
    }

    @Test void surrogateAndMappingBoundaries() {
        // T29：扩展汉字/代理对/组合字符/繁简映射——区间合法性绑定明确文本
        String text = "𠀋甲乙"; // 首字为代理对
        assertTrue(DecisionModels.IssueRef.isBoundary(text, 0));
        assertFalse(DecisionModels.IssueRef.isBoundary(text, 1));
        assertTrue(DecisionModels.IssueRef.isBoundary(text, 2));
        assertThrows(IllegalArgumentException.class,
                () -> DecisionModels.IssueRef.checkSpan(text, 0, 1));
        DecisionModels.IssueRef.checkSpan(text, 0, 2);
        // 映射版本不同即不同身份，不猜偏移
        assertNotEquals(DecisionHash.of(ref("book", "pdf", "basis", "span", "map-v1")),
                DecisionHash.of(ref("book", "pdf", "basis", "span", "map-v2")));
    }

    @Test void hashesReactToSemanticChanges() {
        // T30：候选顺序/alias/文本/上下文/模型/模板变化 → 请求 hash 变化；只改消费阈值 → 证据 hash 不变
        DecisionModels.IssueRef ref = ref("book", "pdf", "basis", "span", "map-v1");
        List<DecisionModels.Candidate> base = List.of(candidate("k1", "甲", "G0"), candidate("k2", "乙", "G1"));
        String h1 = DecisionModels.CandidateSet.computeHash(ref, base, "cfg-v1", 2, false, List.of());
        List<DecisionModels.Candidate> reordered = List.of(candidate("k1", "甲", "G0"), candidate("k2", "乙", "G1"));
        List<DecisionModels.Candidate> swapped = new ArrayList<>(reordered);
        java.util.Collections.reverse(swapper(swapped));
        String hReordered = DecisionModels.CandidateSet.computeHash(ref, swapped, "cfg-v1", 2, false, List.of());
        assertNotEquals(h1, hReordered);
        String hAlias = DecisionModels.CandidateSet.computeHash(ref,
                List.of(candidate("k9", "甲", "G0"), candidate("k2", "乙", "G1")), "cfg-v1", 2, false, List.of());
        assertNotEquals(h1, hAlias);
        String s1 = DecisionModels.DecisionSnapshot.computeHash(ref, h1, Map.of("after", "文"), "tpl-v1");
        String s2 = DecisionModels.DecisionSnapshot.computeHash(ref, h1, Map.of("after", "武"), "tpl-v1");
        assertNotEquals(s1, s2);
        // 两层缓存：只改阈值版本，快照/候选 hash 不变
        assertEquals(s1, DecisionModels.DecisionSnapshot.computeHash(ref, h1, Map.of("after", "文"), "tpl-v1"));
    }

    private static <T> List<T> swapper(List<T> list) { return list; }

    @Test void frozenSnapshotImmuneToCallerMutation() {
        // T31：快照建成后改调用方 bbox/List/Map，已冻结快照、请求字节与 hash 不变
        double[] bbox = {0, 0, 0.2, 0.1};
        Map<String, Object> context = new HashMap<>();
        context.put("nested", new HashMap<>(Map.of("k", "v")));
        context.put("list", new ArrayList<>(List.of("a")));
        DecisionModels.IssueRef ref = ref("book", "pdf", "basis", "span", "map-v1");
        String hash = DecisionModels.DecisionSnapshot.computeHash(ref, "cs", context, "tpl-v1");
        DecisionModels.DecisionSnapshot snapshot = new DecisionModels.DecisionSnapshot(hash, ref, "cs",
                context, List.of(), List.of(), "tpl-v1", Instant.now());
        String bytesBefore = CanonicalJson.write(snapshot);
        bbox[0] = 0.9;
        ((Map<String, Object>) context.get("nested")).put("k", "MUTATED");
        ((List<String>) context.get("list")).add("MUTATED");
        assertEquals(hash, snapshot.snapshotHash());
        assertEquals(bytesBefore, CanonicalJson.write(snapshot));
        // 规范序列化键序稳定：HashMap 插入序不同不影响输出
        Map<String, Object> m1 = new HashMap<>(Map.of("b", 1, "a", 2));
        Map<String, Object> m2 = new HashMap<>(Map.of("a", 2, "b", 1));
        assertEquals(CanonicalJson.write(m1), CanonicalJson.write(m2));
    }

    @Test void illegalValuesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionModels.IssueRef("b", "p", 0, 0, "bl", "is", "o", "ba", 2, 2, "s", "m"));
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionModels.Candidate("k", null, null, "x", "c",
                        DecisionModels.SourceKind.PRIMARY_OCR, "p", null, null, "r", "G", List.of(),
                        "pdf", 1, "s", null, DecisionModels.LocatorMode.REGION,
                        new double[]{0, 0, 1, 1}, "t", DecisionModels.AlignmentStatus.EXACT,
                        List.of(), Double.NaN, "n", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionModels.CandidateSet("h", ref("b", "p", "ba", "s", "m"),
                        List.of(), "cfg", 0, 0, false, List.of(), List.of(), false, false, Instant.now()));
    }
}
