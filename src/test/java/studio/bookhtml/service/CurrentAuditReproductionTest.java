package studio.bookhtml.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Previously reproduced audit failures, now permanent regression contracts. */
class CurrentAuditReproductionTest {
 @TempDir Path temp;
 final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
 @Test void revokedOwnerConsentMustRemainRevokedAfterInitialization() throws Exception {
  BookStore store=new BookStore(TestConfigs.config(temp,"",""),json);
  try {
   var service=new CloudConsentService(store.consentStore(),store.policyStore(),store.epochStore());
   service.init();var before=service.findActiveConsent(null,null);assertNotNull(before);
   service.revokeConsent(null,before.consentId(),service.getReadingPolicy(null).policyRevision(),"audit-revoke");
   assertNull(service.findActiveConsent(null,null));
   var restarted=new CloudConsentService(store.consentStore(),store.policyStore(),store.epochStore());
   restarted.init();
   assertNull(restarted.findActiveConsent(null,null),"startup must not recreate a grant that was explicitly revoked");
  } finally {store.close();}
 }
 @Test void childPlanHashMustReferenceTheReturnedParentHash() {
  var plan=WorkPlan.createDefault("book",1,0,1,"context").freezeReviewSubPlan(4,List.of("a","b","c","d"));
  String expected=WorkPlan.computeReviewPlanHash(plan.bookId(),plan.pageNumber(),plan.eventSeq(),plan.contextHash(),plan.parentPlanHash(),4,List.of("a","b","c","d"));
  assertEquals(expected,plan.reviewPlanHash(),"reviewHash must bind the actual returned parent plan");
 }
 @Test void fourReviewUnitsMustNotCompleteTheirWeightAfterOnlyOne() {
  var progress=new ProcessingProgressService();UUID id=progress.begin("book",1,0);
  progress.plan("book",1,id,"PAGE",1);
  progress.stage("book",1,id,"REVIEW");progress.plan("book",1,id,"TEXT_GROUPS",4);
  progress.unitDone("book",1,id,"first","SUCCEEDED");
  var actual=progress.latest("book",1);assertEquals(4,actual.units().total());assertEquals(1,actual.units().succeeded());
  assertEquals(8,actual.weightedPercent(),"one of four review units should earn 35*1/4, not the full review weight");
 }
 @Test void handwritingMustRejectNonNormalCompletionEvenWithPlausibleJson() throws Exception {
  var fixture=new HandwritingTranscribeServiceTest();
  String envelope=json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","content_filter","message",Map.of("content","{\"text\":\"不完整转录\",\"findings\":[]}")))));
  var service=fixture.service(request->fixture.response(200,envelope));
  assertThrows(OcrException.class,()->service.transcribe(fixture.page(),"vertical",()->false),"non-stop result must not become six accepted regions");
 }
 @Test void manualConfirmedReplacementMustBeAvailableAsContextEvidence() throws Exception {
  BookStore store=new BookStore(TestConfigs.config(temp,"",""),json);String id=UUID.randomUUID().toString();
  try {
   store.createBookDirectory(id);store.writeBook(new Book(id,"fixture","f.pdf",3,Instant.now(),Instant.now(),0,0));
   var issue=new ContentIssue("reviewed-issue","suspected",0,4,0,4,"人工核对",true,"正确术语",null);
   Block b=new Block("b","text",0,null,"horizontal-tb","错误术语","错误术语",.99,false,true,null,"ocr",List.of("b"),null,null,List.of(issue));
   store.writePage(id,new Page(1,600,800,"READY","manual",List.of(b),List.of(),true,null,List.of(b)),false);
   String context=new BookContextService(store,json).snapshot(id,2);
   assertTrue(context.contains("MANUAL_REVIEWED"));
   assertTrue(context.contains("正确术语"),"context called MANUAL_REVIEWED must not silently drop the confirmed correction");
  } finally {store.close();}
 }
 @Test void legacyPagePlanReplacementCannotDiscardFrozenCompletedUnits() {
  var p=new ProcessingProgressService();UUID id=p.begin("book",1,0);
  p.stage("book",1,id,"REVIEW");p.plan("book",1,id,"TEXT_GROUPS",4);p.unitDone("book",1,id,"first","SUCCEEDED");
  var other=WorkPlan.createDefault("another-book",9,0,99,"foreign").freezeReviewSubPlan(0,List.of());
  assertThrows(IllegalArgumentException.class,()->p.plan("book",1,id,other),"typed plan overload must validate owner and already-completed denominator");
 }

 @Test void oldTelemetryCannotOverrideANewerDurableAttempt() throws Exception {
  String book=UUID.randomUUID().toString();UUID oldId=UUID.randomUUID(),newId=UUID.randomUUID();
  var store=org.mockito.Mockito.mock(BookStore.class);
  var page=new Page(1,600,800,"READY","fixture",List.of(),List.of(),false,null,List.of(),1);
  org.mockito.Mockito.when(store.readPage(book,1)).thenReturn(page);
  var authority=org.mockito.Mockito.mock(PageAttempt.class);
  org.mockito.Mockito.when(authority.attemptId()).thenReturn(newId);
  org.mockito.Mockito.when(authority.generation()).thenReturn(2L);
  org.mockito.Mockito.when(authority.lifecycle()).thenReturn("INTERRUPTED");
  org.mockito.Mockito.when(authority.startedAt()).thenReturn(Instant.now());
  org.mockito.Mockito.when(authority.updatedAt()).thenReturn(Instant.now());
  org.mockito.Mockito.when(store.pageAttempt(book,1)).thenReturn(authority);
  var journal=new ProgressJournal(temp.toRealPath());
  journal.recordEvent(book,1,oldId,1,new ProgressJournal.JournalEvent(1,"PLAN_FROZEN","OCR",null,null,null,null,null,null,null,1,Instant.now()));
  journal.recordEvent(book,1,oldId,1,new ProgressJournal.JournalEvent(2,"FINISHED","PUBLISHING",null,null,"SUCCEEDED","old-success",null,null,null,1,Instant.now()));
  var progress=new ProcessingProgressService();progress.setStore(store);progress.setJournal(journal);
  var recovered=progress.latest(book,1);
  assertEquals(newId,recovered.attemptId(),"new durable attempt must win over previous attempt's successful telemetry");
 }

 @Test void damagedRemoteSubmissionRecordMustNotSilentlyFreeItsSlot() throws Exception {
  Path root=temp.toRealPath();String book=UUID.randomUUID().toString();
  var jobs=new RemoteJobRegistry(root,json);
  var one=jobs.register(book,1,"paddle-aistudio","account","input-one","attempt-one");
  jobs.markSubmitting(one.handleId(),"physical-one");
  java.nio.file.Files.writeString(root.resolve("remote-jobs").resolve(one.handleId()+".json"),"{broken-json");
  var restarted=new RemoteJobRegistry(root,json);
  assertThrows(Exception.class,()->restarted.register(book,1,"paddle-aistudio","account","input-one","attempt-two"),
    "a damaged possibly-sent record must require reconciliation, not admit a fresh submission");
 }
}
