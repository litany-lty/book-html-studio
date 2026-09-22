package studio.bookhtml.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsPersistenceTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper();
    Path file() { return data.resolve(".settings/settings.json"); }
    EncryptedFileSecretStore store() { return new EncryptedFileSecretStore(data,new byte[32],"fixture","test"); }
    SettingsService.State state(long revision,String suffix) {
        return new SettingsService.State(revision,"paddle-aistudio",false,"canary-paddle-"+suffix,
                "canary-pp-key-"+suffix,"canary-pp-secret-"+suffix,true,"canary-qwen-"+suffix,"cn-beijing","","model",
                false,"canary-jev-"+suffix,"model",null,false,List.of());
    }
    void legacy(SettingsService.State state) throws Exception {
        PrivateSettingsFiles.ensureDirectory(file().getParent());
        PrivateSettingsFiles.atomicWrite(file(),json.writeValueAsBytes(state));
    }
    @Test void migrationRemovesAllPlaintextAndPreservesRevision() throws Exception {
        var original=state(8,"old"); legacy(original);
        var persistence=new SettingsPersistence(data,json,store());
        var loaded=persistence.read(null); assertEquals(original,loaded);
        persistence.finishLoad(loaded);
        String disk=Files.readString(file());
        for(String value:SettingsPersistence.values(original).values()) assertFalse(disk.contains(value));
        var document=json.readTree(disk); assertEquals(2,document.path("schemaVersion").asInt());
        assertEquals(5,document.path("secretRefs").size());
        for(String slot:SecretStore.SLOTS) assertFalse(document.has(slot));
        assertEquals(original,new SettingsPersistence(data,json,store()).read(null));
        assertFalse(original.toString().contains("canary"));
    }
    @Test void unchangedValuesReuseRefsAndClearIsPersistent() throws Exception {
        var p=new SettingsPersistence(data,json,store()); p.write(state(1,"same"));
        var refs=json.readTree(Files.readString(file())).get("secretRefs");
        p.write(state(2,"same")); assertEquals(refs,json.readTree(Files.readString(file())).get("secretRefs"));
        var empty=new SettingsService.State(3,"paddle-aistudio",false,"","","",false,"","cn-beijing","","model",false,"","model",null,false,List.of());
        p.write(empty); assertEquals(empty,new SettingsPersistence(data,json,store()).read(null));
        try(var files=Files.list(data.resolve(".settings/secrets"))) { assertEquals(0,files.count()); }
    }
    @Test void failedMigrationPublicationKeepsExactLegacyFileAndRemovesPreparedSecrets() throws Exception {
        legacy(state(5,"legacy")); byte[] before=Files.readAllBytes(file());
        var p=new SettingsPersistence(data,json,store(),(path,bytes)->{throw new IOException("injected-disk-failure");});
        var loaded=p.read(null); assertThrows(IOException.class,()->p.finishLoad(loaded));
        assertArrayEquals(before,Files.readAllBytes(file()));
        try(var files=Files.list(data.resolve(".settings/secrets"))) { assertEquals(0,files.count()); }
    }
    @Test void failedNewPublicationDoesNotRemoveOldCredentials() throws Exception {
        var original=state(1,"old"); new SettingsPersistence(data,json,store()).write(original);
        byte[] before=Files.readAllBytes(file());
        var p=new SettingsPersistence(data,json,store(),(path,bytes)->{throw new IOException("injected-disk-failure");});
        p.read(null); assertThrows(IOException.class,()->p.write(state(2,"new")));
        assertArrayEquals(before,Files.readAllBytes(file()));
        assertEquals(original,new SettingsPersistence(data,json,store()).read(null));
        try(var files=Files.list(data.resolve(".settings/secrets"))) { assertEquals(5,files.count()); }
    }
    @Test void cleanupFailureAfterPublicationIsNotSaveFailure() throws Exception {
        SecretStore delegate=store();
        SecretStore failingCleanup=new SecretStore() {
            public Mode mode(){return delegate.mode();}
            public String resolve(String ref){return delegate.resolve(ref);}
            public String put(String slot,String value){return delegate.put(slot,value);}
            public void remove(String ref){delegate.remove(ref);}
            public void retain(Set<String> refs){throw new Failure("INJECTED_CLEANUP_FAILURE");}
        };
        var p=new SettingsPersistence(data,json,failingCleanup);
        assertDoesNotThrow(()->p.write(state(1,"committed"))); assertTrue(p.cleanupPending());
        assertEquals(state(1,"committed"),new SettingsPersistence(data,json,store()).read(null));
    }
    @Test void environmentPersistenceStoresOnlyReferencesAndMigrationMismatchPreservesLegacy() throws Exception {
        var original=state(1,"injected"); var env=SecretStore.environment(SettingsPersistence.values(original));
        new SettingsPersistence(data,json,env).write(original);
        assertFalse(Files.readString(file()).contains("canary"));
        assertEquals(original,new SettingsPersistence(data,json,env).read(null));
        legacy(state(2,"different")); byte[] before=Files.readAllBytes(file());
        var p=new SettingsPersistence(data,json,env); var loaded=p.read(null);
        assertThrows(SecretStore.Failure.class,()->p.finishLoad(loaded));
        assertArrayEquals(before,Files.readAllBytes(file()));
    }
    @Test void emptyEnvironmentSlotsCanBeProvisionedAfterNonSecretSettingsWereSaved() throws Exception {
        var empty=new SettingsService.State(1,"paddle-aistudio",false,"","","",false,"","cn-beijing","","model",false,"","model",null,false,List.of());
        new SettingsPersistence(data,json,SecretStore.environment(Map.of())).write(empty);
        var newEnvironment=SecretStore.environment(Map.of("qwenApiKey","newly-injected-canary"));
        assertEquals("newly-injected-canary",new SettingsPersistence(data,json,newEnvironment).read(null).qwenApiKey());
        assertFalse(Files.readString(file()).contains("newly-injected-canary"));
    }
    @Test void wrongStoreDoesNotRewriteOrFallbackAndMixedPlaintextIsRejected() throws Exception {
        new SettingsPersistence(data,json,store()).write(state(1,"saved")); byte[] before=Files.readAllBytes(file());
        byte[] key=new byte[32];key[0]=1;
        var wrong=new SettingsPersistence(data,json,new EncryptedFileSecretStore(data,key,"fixture","test"));
        assertThrows(SecretStore.Failure.class,()->wrong.read(state(0,"environment")));
        assertArrayEquals(before,Files.readAllBytes(file()));
        ObjectNode node=(ObjectNode)json.readTree(before);node.put("qwenApiKey","untrusted-plaintext");
        PrivateSettingsFiles.atomicWrite(file(),json.writeValueAsBytes(node));
        assertThrows(IOException.class,()->new SettingsPersistence(data,json,store()).read(null));
    }
    @Test void duplicateKeysAndOversizedSettingsAreRejected() throws Exception {
        PrivateSettingsFiles.ensureDirectory(file().getParent());
        PrivateSettingsFiles.atomicWrite(file(),"{\"revision\":1,\"revision\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->new SettingsPersistence(data,json,store()).read(null));
        PrivateSettingsFiles.atomicWrite(file(),new byte[65_537]);
        assertThrows(IOException.class,()->new SettingsPersistence(data,json,store()).read(null));
    }
}
