package studio.bookhtml.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RemoteRecoveryQuarantineTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    RemoteJobRegistry registry;
    @BeforeEach void setup(){registry=new RemoteJobRegistry(temp,json);}
    RemoteJobRegistry.RemoteJobRecord submit(String fingerprint)throws Exception {
        var job=registry.register("book",1,"paddle-aistudio","account",fingerprint,"attempt");
        return registry.markSubmitting(job.handleId(),"physical-"+fingerprint);
    }
    Path path(String id){return temp.resolve("remote-jobs").resolve(id+".json");}
    @Test void deletionOfCorruptReceiptCannotClearPersistentQuarantine()throws Exception {
        var job=submit("bad");Files.writeString(path(job.handleId()),"{corrupt");
        var restarted=new RemoteJobRegistry(temp,json);assertTrue(restarted.recoveryBlocked());
        Files.delete(path(job.handleId()));
        var again=new RemoteJobRegistry(temp,json);
        assertTrue(again.recoveryBlocked());assertEquals(1,again.quarantinedRecordCount());
        assertThrows(Exception.class,()->again.register("book",1,"paddle-aistudio","account","bad","new-attempt"));
    }
    @Test void restoringValidatedReceiptKeepsTheSameUnknownDebt()throws Exception {
        var job=submit("bad");byte[] original=Files.readAllBytes(path(job.handleId()));
        Files.writeString(path(job.handleId()),"{corrupt");
        var restarted=new RemoteJobRegistry(temp,json);assertTrue(restarted.recoveryBlocked());
        Files.write(path(job.handleId()),original);restarted.recover();
        assertFalse(restarted.recoveryBlocked());assertEquals(1,restarted.activeJobCount());
        assertEquals(job.handleId(),restarted.register("book",1,"paddle-aistudio","account","bad","new").handleId());
        assertThrows(Exception.class,()->restarted.markSubmitting(job.handleId(),"duplicate"));
    }
    @Test void knownRunningHandleCanResumeButNoNewSendWhileOtherReceiptIsBad()throws Exception {
        var good=submit("good");registry.markRunning(good.handleId(),"remote-task");
        var bad=submit("bad");Files.writeString(path(bad.handleId()),"{broken");
        var restarted=new RemoteJobRegistry(temp,json);
        assertEquals(good.handleId(),restarted.register("book",1,"paddle-aistudio","account","good","resume").handleId());
        assertThrows(Exception.class,()->restarted.register("book",2,"paddle-aistudio","account","third","new"));
        assertEquals("{broken",Files.readString(path(bad.handleId())));
    }
    @Test void validJsonWithChangedHandleIsQuarantined()throws Exception {
        var job=submit("bad");var data=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(path(job.handleId())));
        data.put("handleId",UUID.randomUUID().toString());Files.write(path(job.handleId()),json.writeValueAsBytes(data));
        assertTrue(new RemoteJobRegistry(temp,json).recoveryBlocked());
    }
    @Test void duplicateJsonKeyDoesNotOverwriteItsOwnState()throws Exception {
        var job=submit("bad");String data=Files.readString(path(job.handleId()));
        Files.writeString(path(job.handleId()),data.substring(0,data.length()-1)+",\"state\":\"TERMINAL_PROVEN\"}");
        assertTrue(new RemoteJobRegistry(temp,json).recoveryBlocked());
    }
    @Test void missingLiveReceiptIsQuarantinedDuringSameProcessReconcile()throws Exception {
        var job=submit("bad");Files.delete(path(job.handleId()));registry.recover();
        assertTrue(registry.recoveryBlocked());assertEquals(1,registry.activeJobCount());
        assertThrows(Exception.class,()->registry.register("book",2,"paddle-aistudio","account","other","new"));
    }
}
