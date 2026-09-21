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
        config.setMode("SHADOW");
        config.setProvider("MOCK");
        config.setApiKey("test-key");
        config.setModel("mock-model");
        config.setAllowCloudData(true);
        config.setMonetaryBudgetMinor(100L);
        MockDecisionTransport transport = new MockDecisionTransport();
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
        assertEquals("CANDIDATES_ONLY", entry.get("verdict"));
        assertNotNull(entry.get("candidateSetHash"));
        assertNotNull(entry.get("decisionId"));
        assertEquals("question-template-v1", entry.get("templateVersion"));
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
        Files.writeString(source, "%PDF-decision");
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
