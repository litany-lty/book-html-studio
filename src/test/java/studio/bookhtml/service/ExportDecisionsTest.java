package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.decision.CandidateResolutionService;
import studio.bookhtml.decision.DecisionAcceptService;
import studio.bookhtml.decision.DecisionBudget;
import studio.bookhtml.decision.DecisionCoordinator;
import studio.bookhtml.decision.DecisionStateBuilder;
import studio.bookhtml.decision.DecisionStore;
import studio.bookhtml.decision.EvidenceCollector;
import studio.bookhtml.decision.IssueBasis;
import studio.bookhtml.decision.JevDecisionClient;
import studio.bookhtml.decision.MockDecisionTransport;
import studio.bookhtml.decision.PdfIdentity;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.CommitOp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * J10（T62 导出侧）：只读建议摘要有界脱敏、无密钥、过期排除；离线无远端可用照常导出。
 */
class ExportDecisionsTest {
    @TempDir Path temp;

    private static final String BOOK = "11111111-2222-3333-4444-555555555555";

    /** JR-08-T06：高分合成传输，仅导出测试用，产生可放行正式推荐（不改全局 Mock）。 */
    static class HighScoreTransport extends MockDecisionTransport {
        @Override
        public studio.bookhtml.service.BoundedHttp.Response send(java.net.http.HttpRequest request,
                long deadlineNanos, int maxBytes, java.util.function.BooleanSupplier cancelled)
                throws java.io.IOException {
            calls.incrementAndGet();
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                request.bodyPublisher().ifPresent(publisher -> publisher.subscribe(
                        new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                s.request(Long.MAX_VALUE);
                            }
                            public void onNext(java.nio.ByteBuffer item) {
                                byte[] chunk = new byte[item.remaining()];
                                item.get(chunk);
                                out.write(chunk, 0, chunk.length);
                            }
                            public void onError(Throwable t) { done.countDown(); }
                            public void onComplete() { done.countDown(); }
                        }));
                done.await(5, java.util.concurrent.TimeUnit.SECONDS);
                com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> body = json.readValue(out.toByteArray(), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> questions =
                        (Map<String, Object>) body.getOrDefault("questions", Map.of());
                Map<String, Object> answers = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> e : questions.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> question = (Map<String, Object>) e.getValue();
                    String type = String.valueOf(question.getOrDefault("type", ""));
                    @SuppressWarnings("unchecked")
                    Map<String, String> criteria =
                            (Map<String, String>) question.getOrDefault("criteria", Map.of());
                    if ("choice".equals(type)) {
                        String first = criteria.keySet().stream().sorted().findFirst().orElse("C0");
                        Map<String, Double> distribution = new java.util.LinkedHashMap<>();
                        double rest = criteria.isEmpty() ? 0 : 0.05 / Math.max(1, criteria.size() - 1);
                        for (String key : criteria.keySet())
                            distribution.put(key, key.equals(first) ? 0.95 : rest);
                        answers.put(e.getKey(), Map.of("type", "choice", "choice", first,
                                "probabilities", distribution, "confidence", 0.9));
                    } else if ("noul".equals(type)) {
                        answers.put(e.getKey(), Map.of("type", "noul", "noul", 0.0));
                    } else if ("score".equals(type)) {
                        answers.put(e.getKey(), Map.of("type", "score", "score", 2.0,
                                "probabilities", Map.of("2", 1.0), "confidence", 0.9));
                    } else {
                        answers.put(e.getKey(), Map.of("type", type));
                    }
                }
                Map<String, Object> response = new java.util.LinkedHashMap<>();
                response.put("answers", answers);
                response.put("model", "mock-synthetic-high");
                byte[] bytes = json.writeValueAsBytes(response);
                return new studio.bookhtml.service.BoundedHttp.Response(200, bytes);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("mock transport 失败", e);
            }
        }
    }

    private static Block block(String id, String original) {
        return new Block(id, "text", 0, new double[]{0, 0, 0.4, 0.2}, "horizontal-tb",
                original, original, 0.9, true, false, null, "paddle", List.of(id),
                "疑点", new double[]{0, 0, 40, 20},
                List.of(new ContentIssue("i-" + id, "suspected", 0, 1, 0, 1, "理由", false, null, "推测")));
    }

    private record Fixture(BookStore store, DecisionCoordinator coordinator,
                           DecisionProperties config, MockDecisionTransport transport) {}

    private Fixture fixture() throws Exception {
        AppProperties app = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "m", "u", 5,
                "", "m", "u", 5, true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BookStore store = new BookStore(app, mapper);
        store.createBookDirectory(BOOK);
        store.writeBook(new Book(BOOK, "t", "t.pdf", 9, Instant.now(), Instant.now(), 0, 0));
        Files.write(store.pdf(BOOK), "pdf-bytes".getBytes());
        Block b1 = block("b1", "甲乙");
        store.writePage(BOOK, new Page(3, 600, 800, "READY", "paddle", List.of(b1),
                List.of(), false, null, List.of(b1)), false);
        DecisionStore decisions = new DecisionStore(store, mapper);
        DecisionBudget budget = new DecisionBudget(decisions);
        CandidateResolutionService resolution =
                new CandidateResolutionService(new TraditionalConverter());
        QwenOcrClient qwen = mock(QwenOcrClient.class);
        when(qwen.configured()).thenReturn(false);
        IssueImageService images = mock(IssueImageService.class);
        DecisionProperties config = new DecisionProperties();
        config.setMode("ASSIST");
        config.setProvider("MOCK");
        config.setApiKey("test-key");
        config.setModel("mock-model");
        config.setAllowCloudData(true);
        config.setMonetaryBudgetMinor(100L);
        // JR-08-T03/T06：导出仅含正式推荐；测试用匹配档产生 RECOMMEND/KEEP_CURRENT
        config.setCalibrationStatus("VALIDATED");
        config.setCalibrationProfile("cal-v1|model=mock-model|template=question-template-v2"
                + "|candidate=candidate-config-v3|policy=decision-policy-v1"
                + "|threshold=pilot-default-v1|dataset=test|result=test-ok");
        MockDecisionTransport transport = new HighScoreTransport();
        EvidenceCollector evidence = new EvidenceCollector(resolution, images, qwen, budget, config);
        JevDecisionClient jev = new JevDecisionClient(mapper, transport);
        DecisionCoordinator coordinator = new DecisionCoordinator(store, decisions, budget,
                new PdfIdentity(), resolution, evidence, new DecisionStateBuilder(), jev,
                config, transport, mapper);
        return new Fixture(store, coordinator, config, transport);
    }

    private void prepareDecision(Fixture f) throws Exception {
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().get(0);
        ContentIssue issue = block.issues().get(0);
        DecisionCoordinator.CreateResult created = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                new DecisionCoordinator.CreateBody("op-export", "b1",
                        BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));
        f.coordinator.runInline(BOOK, created.job().jobId());
        assertEquals("SUCCEEDED", f.coordinator.queryJob(BOOK, created.job().jobId()).state());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseScript(String script) throws Exception {
        assertTrue(script.startsWith("globalThis.BOOK_DECISIONS="));
        String json = script.substring("globalThis.BOOK_DECISIONS=".length());
        if (json.endsWith(";")) json = json.substring(0, json.length() - 1);
        return new ObjectMapper().readValue(json, Map.class);
    }

    @Test void summaryBoundedDesensitizedAndCurrentOnly() throws Exception {
        Fixture f = fixture();
        ExportService service = new ExportService(mock(BookService.class), f.store,
                mock(PdfService.class), new ObjectMapper().findAndRegisterModules(), null,
                f.coordinator);
        // 无建议时为空映射
        assertTrue(parseScript(service.decisionsScript(BOOK, List.of(3), Map.of(3, 3))).isEmpty());
        prepareDecision(f);
        Map<String, Object> summaries =
                parseScript(service.decisionsScript(BOOK, List.of(3), Map.of(3, 3)));
        assertEquals(1, summaries.size());
        Map<String, Object> entry = (Map<String, Object>) summaries.get("3:b1:i-b1");
        assertNotNull(entry);
        assertEquals(3, entry.get("sourcePage"));
        assertEquals("b1", entry.get("blockId"));
        assertEquals("i-b1", entry.get("issueId"));
        assertEquals("甲", entry.get("originalQuote"));
        assertNotNull(entry.get("candidateId"));
        assertNotNull(entry.get("displayText"));
        // JR-08：高分匹配档下正式推荐为 KEEP_CURRENT（当前转录正确时保持）；仅 admitted 导出
        assertEquals("KEEP_CURRENT", entry.get("verdict"));
        assertNotNull(entry.get("candidateSetHash"));
        assertNotNull(entry.get("decisionId"));
        assertEquals("question-template-v2", entry.get("templateVersion"));
        assertEquals("decision-policy-v1", entry.get("policyVersion"));
        String raw = new ObjectMapper().writeValueAsString(summaries);
        assertFalse(raw.contains("test-key"), "不得泄漏密钥");
        assertFalse(raw.contains("Authorization"));
        assertFalse(raw.contains("Bearer"));
        // 页面推进后过期排除
        Page saved = f.store.readPage(BOOK, 3);
        Block changed = block("b1", "甲乙改");
        Page proposed = new Page(3, 600, 800, "READY", "manual",
                List.of(changed), List.of(), false, null, saved.sourceRecords(), null);
        f.store.commitPage(BOOK, proposed, BookStore.revisionOrZero(saved),
                CommitActor.MANUAL, null, CommitOp.MANUAL_SAVE);
        assertTrue(parseScript(service.decisionsScript(BOOK, List.of(3), Map.of(3, 3))).isEmpty());
    }

    @Test void writeZipContainsDecisionsAsset() throws Exception {
        Fixture f = fixture();
        prepareDecision(f);
        BookService books = mock(BookService.class);
        when(books.get(BOOK)).thenReturn(new Book(BOOK, "t", "t.pdf", 9, Instant.now(),
                Instant.now(), 0, 0));
        PdfService pdf = mock(PdfService.class);
        Path source = f.store.pdf(BOOK);
        // JR-01：来源 PDF 已在 fixture 写入；此处不覆盖，避免决策快照 STALE（render 已 mock）
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(120, 160, java.awt.image.BufferedImage.TYPE_INT_RGB);
        when(pdf.render(eq(source), eq(3), anyInt())).thenReturn(image);
        ExportService service = new ExportService(books, f.store, pdf,
                new ObjectMapper().findAndRegisterModules(), mock(IssueImageService.class),
                f.coordinator);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip(BOOK, output, "3");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        assertTrue(entries.containsKey("assets/decisions.js"));
        String script = new String(entries.get("assets/decisions.js"), StandardCharsets.UTF_8);
        assertEquals(1, parseScript(script).size());
        image.flush();
    }
}
