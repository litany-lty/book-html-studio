package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PagePublicationAuthorityTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString(), job="reading:"+UUID.randomUUID();
    CheckpointStore store;
    static final class Crash extends Error {}
    final class CheckpointStore extends BookStore {
        String failure, crash;
        CheckpointStore() throws IOException { super(TestConfigs.config(data,"",""),json); }
        @Override protected void commitCheckpoint(String phase) throws IOException {
            if (phase.equals(crash)) throw new Crash();
            if (phase.equals(failure)) throw new IOException("synthetic checkpoint failure");
            super.commitCheckpoint(phase);
        }
    }
    @BeforeEach void setup() throws Exception {
        store=new CheckpointStore(); store.createBookDirectory(book);
        store.writeBook(new Book(book,"fixture","fixture.pdf",2,Instant.now(),Instant.now(),0,0));
        for(int n=1;n<=2;n++) store.writePage(book,page(n,"原有正文"),false);
        Files.writeString(store.pdf(book),"synthetic source A");
        store.writeJob(book,new Job(job,"RUNNING",0,0,null,null,List.of(),Instant.now(),
                List.of(),"paddle-aistudio","auto",false,false,false,"fixture"));
    }
    @AfterEach void close() { BookStore.clearIoFailure(); if(store!=null)store.close(); }
    Page page(int number,String text) {
        var block=new Block("b1","text",0,new double[]{.1,.1,.8,.2},"horizontal-tb",text,text,
                .99,false,false,null,"paddle",List.of("b1"),null,null);
        return new Page(number,600,800,"READY","paddle-aistudio",List.of(block),List.of(),false,null,List.of(block));
    }
    PageAttempt owner(int page) throws Exception {
        return store.registerPageAttempt(book,page,store.readPage(book,page).revision(),job,
                List.of("JOB_BASELINE","JOB_ENHANCEMENT","JOB_COMPLETE","JOB_RESTORE"),false,null,null);
    }
    Page publish(PageAttempt a,String text,String outcome,CommitOp op,int revision) throws Exception {
        return store.commitPage(book,page(a.pageNumber(),text),revision,CommitActor.JOB,a.commitIdentity(outcome),op);
    }
    com.fasterxml.jackson.databind.JsonNode log() throws Exception {
        return json.readTree(store.bookDir(book).resolve("pages/commits/1.json").toFile());
    }
    @Test void reservationPrefixAloneCannotPublish() throws Exception {
        var a=owner(1); int revision=a.expectedRevision();
        assertThrows(PageConflictException.class,()->store.commitPage(book,page(1,"伪造任务"),revision,
                CommitActor.JOB,job+":1",CommitOp.JOB_BASELINE));
        assertEquals(revision,store.readPage(book,1).revision());
    }
    @Test void wrongPageAttemptAndSequenceCannotPublish() throws Exception {
        var a=owner(1); owner(2);
        assertThrows(PageConflictException.class,()->store.commitPage(book,page(2,"串页"),0,
                CommitActor.JOB,a.commitIdentity("SUCCEEDED"),CommitOp.JOB_BASELINE));
        String wrong="attempt:"+a.attemptId()+":"+(a.generation()+1)+":SUCCEEDED";
        assertThrows(PageConflictException.class,()->store.commitPage(book,page(1,"串代"),0,CommitActor.JOB,wrong,CommitOp.JOB_BASELINE));
        String unknown="attempt:"+UUID.randomUUID()+":1:SUCCEEDED";
        assertThrows(PageConflictException.class,()->store.commitPage(book,page(1,"无主"),0,CommitActor.JOB,unknown,CommitOp.JOB_BASELINE));
    }
    @Test void actorCannotBorrowSystemOrManualOperations() throws Exception {
        var a=owner(1);
        assertThrows(ApiException.class,()->store.commitPage(book,page(1,"不匹配"),0,CommitActor.JOB,
                a.commitIdentity("SUCCEEDED"),CommitOp.SYSTEM_RECOVERY));
        assertThrows(ApiException.class,()->store.commitPage(book,page(1,"不匹配"),0,CommitActor.SYSTEM,null,CommitOp.MANUAL_SAVE));
    }
    @Test void aNewAttemptRevokesOldWorkerEvenWithinSameReservation() throws Exception {
        var old=owner(1); store.finishPageAttempt(old,"FAILED"); var next=owner(1);
        assertEquals(old.generation()+1,next.generation());
        assertThrows(PageConflictException.class,()->publish(old,"过期结果","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        assertEquals("新结果",publish(next,"新结果","SUCCEEDED",CommitOp.JOB_BASELINE,0).blocks().get(0).original());
    }
    @Test void undeclaredOperationCannotBeAddedByWorker() throws Exception {
        var a=store.registerPageAttempt(book,1,0,job,List.of("JOB_BASELINE"),false,null,null);
        assertThrows(PageConflictException.class,()->publish(a,"不许可","SUCCEEDED",CommitOp.JOB_COMPLETE,0));
    }
    @Test void changedSourceSameSizeAndMtimeRejectsOldResult() throws Exception {
        var a=owner(1); Path pdf=store.pdf(book); var modified=Files.getLastModifiedTime(pdf);
        Path changed=Files.createTempFile(pdf.getParent(),"fixture-source-",".tmp");
        Files.writeString(changed,"synthetic source B"); Files.setLastModifiedTime(changed,modified);
        Files.move(changed,pdf,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        assertThrows(PageConflictException.class,()->publish(a,"旧图结果","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        assertEquals("原有正文",store.readPage(book,1).blocks().get(0).original());
    }
    @Test void originalBlockIdAloneIsNotSourceIdentity() throws Exception {
        var a=owner(1);
        // Corrupt a source without advancing revision, keeping the same block ID.
        json.writeValue(store.pagePath(book,1).toFile(),BookStore.withRevision(page(1,"来源已换"),0));
        assertThrows(PageConflictException.class,()->publish(a,"旧来源结果","SUCCEEDED",CommitOp.JOB_BASELINE,0));
    }
    @Test void manualEditCannotBeOverwrittenEvenWithGuessedNewRevision() throws Exception {
        var a=owner(1);
        Page manual=store.commitPage(book,page(1,"人工更新"),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        assertThrows(PageConflictException.class,()->publish(a,"旧输出","SUCCEEDED",CommitOp.JOB_BASELINE,manual.revision()));
        assertEquals("人工更新",store.readPage(book,1).blocks().get(0).original());
    }
    @Test void cancellationDeniesBothLatePublicationAndRestore() throws Exception {
        var a=owner(1); byte[] before=Files.readAllBytes(store.pagePath(book,1)); store.revokePageAttempt(a);
        assertThrows(PageConflictException.class,()->publish(a,"取消后结果","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        assertThrows(PageConflictException.class,()->publish(a,"取消后恢复","FAILED",CommitOp.JOB_RESTORE,0));
        assertArrayEquals(before,Files.readAllBytes(store.pagePath(book,1)));
    }
    @Test void failedCancellationWriteStillClosesInProcessGate() throws Exception {
        var a=owner(1); BookStore.failNextIoAt("atomic");
        try { assertThrows(IOException.class,()->store.revokePageAttempt(a)); }
        finally { BookStore.clearIoFailure(); }
        assertThrows(PageConflictException.class,()->publish(a,"迟到结果","SUCCEEDED",CommitOp.JOB_BASELINE,0));
    }
    @Test void baselineThenEnhancementUsesActualOwnedRevision() throws Exception {
        var a=owner(1); var first=publish(a,"可读基线","BASELINE_PUBLISHED",CommitOp.JOB_BASELINE,0);
        store.finishPageAttempt(a,"BASELINE_PUBLISHED");
        var finalPage=publish(a,"增强正文","SUCCEEDED",CommitOp.JOB_ENHANCEMENT,first.revision());
        assertEquals(first.revision()+1,finalPage.revision()); assertNotEquals(first.lastCommitId(),finalPage.lastCommitId());
        assertEquals("SUCCEEDED",store.recoveredAttemptOutcome(a));
    }
    @Test void replaySameCommitIsReadOnlyAndDifferentResultConflicts() throws Exception {
        var a=owner(1); var events=new ArrayList<BookStore.PageChange>(); store.addPageChangeListener(events::add);
        Page first=publish(a,"正式正文","SUCCEEDED",CommitOp.JOB_BASELINE,0);
        store.finishPageAttempt(a,"SUCCEEDED");
        Page again=publish(a,"正式正文","SUCCEEDED",CommitOp.JOB_BASELINE,0);
        assertEquals(json.valueToTree(first),json.valueToTree(again)); assertEquals(1,events.size()); assertNotNull(first.lastCommitId());
        assertThrows(PageConflictException.class,()->publish(a,"另一正文","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        assertEquals(json.valueToTree(first),json.valueToTree(store.readPage(book,1)));
    }
    @Test void incompleteEnhancementCannotBeRecoveredAsFullSuccess() throws Exception {
        var a=owner(1); Page baseline=publish(a,"基础稿","BASELINE_PUBLISHED",CommitOp.JOB_BASELINE,0);
        publish(a,"部分核对稿","PARTIAL",CommitOp.JOB_ENHANCEMENT,baseline.revision());
        assertEquals("PARTIAL",store.recoveredAttemptOutcome(a));
    }
    @Test void intentOrPageWriteFailureDoesNotChangePublishedBytes() throws Exception {
        var a=owner(1); byte[] before=Files.readAllBytes(store.pagePath(book,1));
        store.failure="commit-intent";
        assertThrows(IOException.class,()->publish(a,"未发布","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        assertArrayEquals(before,Files.readAllBytes(store.pagePath(book,1)));
        store.failure="page-replace";
        assertThrows(IOException.class,()->publish(a,"未发布","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        assertArrayEquals(before,Files.readAllBytes(store.pagePath(book,1)));
        assertEquals("NOT_PUBLISHED",log().path("entries").get(0).path("state").asText());
    }
    @Test void completionWriteFailureStillReturnsSavedPageAndRecoverySettlesIt() throws Exception {
        var a=owner(1); store.failure="commit-completion";
        Page saved=publish(a,"已发布正文","SUCCEEDED",CommitOp.JOB_BASELINE,0);
        assertNotNull(saved.lastCommitId()); assertEquals("PREPARED",log().path("entries").get(0).path("state").asText());
        store.failure=null; store.recoverPagePublications(); store.reconcilePageAttempts(book);
        assertEquals(json.valueToTree(saved),json.valueToTree(store.readPage(book,1)));
        assertEquals("COMMITTED",log().path("entries").get(0).path("state").asText());
        assertEquals("SUCCEEDED",store.readSidecar(store.pageAttemptsPath(book),PageAttempt.Journal.class).intents().get(book+":1").lifecycle());
    }
    @Test void crashBeforeRenameDoesNotMakeReadyPageProofOfSuccess() throws Exception {
        var a=owner(1); byte[] before=Files.readAllBytes(store.pagePath(book,1)); store.crash="commit-prepared";
        assertThrows(Crash.class,()->publish(a,"尚未提交","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        store.crash=null; store.recoverPagePublications(); store.reconcilePageAttempts(book);
        assertArrayEquals(before,Files.readAllBytes(store.pagePath(book,1)));
        assertEquals("NOT_PUBLISHED",log().path("entries").get(0).path("state").asText());
        assertEquals("INTERRUPTED",store.readSidecar(store.pageAttemptsPath(book),PageAttempt.Journal.class).intents().get(book+":1").lifecycle());
    }
    @Test void crashAfterRenameRestoresOnlyMatchingAttemptOutcome() throws Exception {
        var a=owner(1); store.crash="page-published";
        assertThrows(Crash.class,()->publish(a,"写入已完成","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        Page saved=store.readPage(book,1); store.crash=null;
        assertNotNull(saved.lastCommitId()); store.recoverPagePublications(); store.reconcilePageAttempts(book);
        assertEquals(json.valueToTree(saved),json.valueToTree(store.readPage(book,1)));
        assertEquals("SUCCEEDED",store.readSidecar(store.pageAttemptsPath(book),PageAttempt.Journal.class).intents().get(book+":1").lifecycle());
    }
    @Test void aPublishedBaselineDoesNotProveMissingEnhancementCompleted() throws Exception {
        var a=owner(1); store.crash="page-published";
        assertThrows(Crash.class,()->publish(a,"安全基线","BASELINE_PUBLISHED",CommitOp.JOB_BASELINE,0));
        store.crash=null; store.recoverPagePublications(); store.reconcilePageAttempts(book);
        assertEquals("安全基线",store.readPage(book,1).blocks().get(0).original());
        assertEquals("INTERRUPTED",store.readSidecar(store.pageAttemptsPath(book),PageAttempt.Journal.class).intents().get(book+":1").lifecycle());
    }
    @Test void correctCommitMarkerButChangedContentIsNotProof() throws Exception {
        var a=owner(1); store.crash="page-published";
        assertThrows(Crash.class,()->publish(a,"原提交","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        var tampered=json.valueToTree(store.readPage(book,1));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tampered.path("blocks").get(0)).put("original","替换内容");
        json.writeValue(store.pagePath(book,1).toFile(),tampered); store.crash=null;
        store.recoverPagePublications(); store.reconcilePageAttempts(book);
        assertNotEquals("SUCCEEDED",store.readSidecar(store.pageAttemptsPath(book),PageAttempt.Journal.class).intents().get(book+":1").lifecycle());
        assertEquals("UNKNOWN",log().path("entries").get(0).path("state").asText());
        assertThrows(IOException.class,()->owner(1));
    }
    @Test void logRemainsBoundedAndManualSaveNeverReusesCallerCommitId() throws Exception {
        UUID previous=null;
        for(int n=0;n<25;n++) {
            Page current=store.readPage(book,1);
            Page saved=store.commitPage(book,BookStore.withRevision(current,n),current.revision(),
                    CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
            assertNotNull(saved.lastCommitId()); assertNotEquals(previous,saved.lastCommitId()); previous=saved.lastCommitId();
        }
        assertEquals(16,log().path("entries").size());
        assertTrue(log().path("entries").toString().contains("COMMITTED"));
    }
    @Test void malformedOrFutureCommitJournalBlocksNewPublicationWithoutChangingPage() throws Exception {
        Path path=store.bookDir(book).resolve("pages/commits/1.json"); Files.createDirectories(path.getParent());
        byte[] before=Files.readAllBytes(store.pagePath(book,1));
        for(String value:List.of("{bad", "{\"schemaVersion\":99,\"entries\":[]}",
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"entries\":[]}","{\"schemaVersion\":1.1,\"entries\":[]}")) {
            Files.writeString(path,value);
            assertThrows(IOException.class,()->store.commitPage(book,page(1,"不可发布"),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE));
            assertArrayEquals(before,Files.readAllBytes(store.pagePath(book,1)));
        }
    }
    @Test void journalFractionalOrStringSequenceCannotAuthorizePublication() throws Exception {
        var a=owner(1); Path path=store.pageAttemptsPath(book);
        byte[] before=Files.readAllBytes(path);
        for(String malformed:List.of("1.5","\"1\"","9223372036854775808")) {
            String value=new String(before,java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\"generation\":1","\"generation\":"+malformed);
            assertFalse(value.equals(new String(before,java.nio.charset.StandardCharsets.UTF_8)));
            Files.writeString(path,value);
            assertThrows(ApiException.class,()->publish(a,"非法代次","SUCCEEDED",CommitOp.JOB_BASELINE,0));
        }
        Files.write(path,before);
        Page saved=publish(a,"有效结果","SUCCEEDED",CommitOp.JOB_BASELINE,0);
        assertEquals("有效结果",saved.blocks().get(0).original());
    }
    @Test void newAttemptsCannotReintroduceProcessingPageWrites() throws Exception {
        assertThrows(IOException.class,()->store.registerPageAttempt(book,1,0,job,List.of("JOB_START"),false,null,null));
        assertEquals("READY",store.readPage(book,1).status());
    }

    @Test void cancellationFailureIsSharedAcrossStorageHandles() throws Exception {
        var a=owner(1); BookStore.failNextIoAt("atomic");
        try { assertThrows(IOException.class,()->store.revokePageAttempt(a)); }
        finally { BookStore.clearIoFailure(); }
        BookStore other=new BookStore(TestConfigs.config(data,"",""),json);
        try {
            assertThrows(PageConflictException.class,()->other.commitPage(book,page(1,"旧结果"),0,
                    CommitActor.JOB,a.commitIdentity("SUCCEEDED"),CommitOp.JOB_BASELINE));
        } finally { other.close(); }
        assertEquals("原有正文",store.readPage(book,1).blocks().get(0).original());
    }

    @Test void confirmedIssueCountsAsManualWorkEvenWhenPageIsNotReviewed() throws Exception {
        var issue=new ContentIssue("confirmed","suspected",0,1,0,1,"fixture",true,"确",null,null);
        var block=new Block("b1","text",0,new double[]{.1,.1,.8,.2},"horizontal-tb","原有正文","原有正文",
                .99,false,false,null,"paddle",List.of("b1"),null,null,List.of(issue));
        store.writePage(book,new Page(1,600,800,"READY","paddle-aistudio",List.of(block),List.of(),false,null,List.of(block)),false);
        int revision=store.readPage(book,1).revision();
        assertThrows(PageConflictException.class,()->owner(1));
        var authorized=store.registerPageAttempt(book,1,revision,job,List.of("JOB_BASELINE"),true,null,null);
        assertEquals("明确授权的新稿",publish(authorized,"明确授权的新稿","SUCCEEDED",CommitOp.JOB_BASELINE,revision).blocks().get(0).original());
    }

}
