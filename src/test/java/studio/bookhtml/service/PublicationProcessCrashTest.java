package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Kills only a newly spawned, isolated fixture JVM, never the application or host. */
class PublicationProcessCrashTest {
    @TempDir Path temp;
    static final String BOOK="77777777-1234-1234-1234-123456789012";
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    @Test void processExitBeforePageReplaceKeepsOldPage() throws Exception { run("commit-prepared",0,"INTERRUPTED"); }
    @Test void processExitAfterPageReplaceRepairsCompletion() throws Exception { run("page-published",1,"SUCCEEDED"); }
    @Test void processExitAfterCompletedRecordStillRepairsAttempt() throws Exception { run("commit-settled",1,"SUCCEEDED"); }
    void run(String phase,int revision,String lifecycle) throws Exception {
        Path data=temp.resolve("fixture-data");
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        ProcessBuilder builder=new ProcessBuilder(java,"-Djava.awt.headless=true","-cp",
                System.getProperty("java.class.path"),Child.class.getName(),data.toString(),phase);
        var env=builder.environment(); env.keySet().removeIf(k->!Set.of("HOME","PATH","TMPDIR","LANG","LC_ALL","SystemRoot").contains(k));
        builder.redirectErrorStream(true).redirectOutput(temp.resolve("fixture-jvm.log").toFile());
        Process child=builder.start();
        try {
            assertTrue(child.waitFor(20,TimeUnit.SECONDS),"isolated fixture did not reach checkpoint");
            assertEquals(73,child.exitValue());
        } finally { if(child.isAlive()) child.destroyForcibly().waitFor(5,TimeUnit.SECONDS); }
        assertEquals(phase,Files.readString(data.resolve("checkpoint.txt")));
        try(BookStoreHolder holder=new BookStoreHolder(data)) {
            BookStore store=holder.store;
            store.recoverPagePublications(); store.reconcilePageAttempts(BOOK);
            Page saved=store.readPage(BOOK,1); assertEquals(revision,saved.revision());
            assertEquals(revision==0?"原有正文":"新正文",saved.blocks().get(0).original());
            var journal=store.readSidecar(store.pageAttemptsPath(BOOK),PageAttempt.Journal.class);
            assertEquals(lifecycle,journal.intents().get(BOOK+":1").lifecycle());
            store.recoverPagePublications(); store.reconcilePageAttempts(BOOK);
            assertEquals(JSON.valueToTree(saved),JSON.valueToTree(store.readPage(BOOK,1)),"recovery is read-only for page content");
        }
    }
    static final class BookStoreHolder implements AutoCloseable {
        final BookStore store;
        BookStoreHolder(Path data) throws Exception { store=new BookStore(TestConfigs.config(data,"",""),JSON); }
        public void close() { store.close(); }
    }
    public static final class Child {
        public static void main(String[] args) throws Exception {
            if(args.length!=2 || !Set.of("commit-prepared","page-published","commit-settled").contains(args[1]))
                throw new IllegalArgumentException("fixture checkpoint required");
            Path data=Path.of(args[0]).toAbsolutePath().normalize();
            Path temp=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            if(Files.exists(data) || !data.getParent().toRealPath().startsWith(temp))
                throw new IllegalArgumentException("only new isolated temporary data is allowed");
            BookStore store=new BookStore(TestConfigs.config(data,"",""),JSON) {
                @Override protected void commitCheckpoint(String phase) throws java.io.IOException {
                    if(phase.equals(args[1])) {
                        Files.writeString(data.resolve("checkpoint.txt"),phase);
                        Runtime.getRuntime().halt(73); // This fixture JVM only; bypass normal shutdown for recovery testing.
                    }
                }
            };
            store.createBookDirectory(BOOK);
            store.writeBook(new Book(BOOK,"fixture","fixture.pdf",1,Instant.now(),Instant.now(),0,0));
            store.writePage(BOOK,page("原有正文"),false);
            Files.writeString(store.pdf(BOOK),"synthetic source fixture");
            String job="reading:"+UUID.randomUUID();
            store.writeJob(BOOK,new Job(job,"RUNNING",0,0,null,null,List.of(),Instant.now(),
                    List.of(),"paddle-aistudio","auto",false,false,false,"fixture"));
            var a=store.registerPageAttempt(BOOK,1,0,job,List.of("JOB_BASELINE"),false,null,null);
            store.commitPage(BOOK,page("新正文"),0,CommitActor.JOB,a.commitIdentity("SUCCEEDED"),CommitOp.JOB_BASELINE);
            throw new AssertionError("checkpoint was not reached");
        }
        private static Page page(String text) {
            Block block=new Block("b1","text",0,new double[]{.1,.1,.8,.2},"horizontal-tb",text,text,
                    .99,false,false,null,"paddle",List.of("b1"),null,null);
            return new Page(1,600,800,"READY","paddle-aistudio",List.of(block),List.of(),false,null,List.of(block));
        }
    }
}
