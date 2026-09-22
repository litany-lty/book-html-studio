package studio.bookhtml.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecretStoreTest {
    @TempDir Path data;
    private EncryptedFileSecretStore store() { return new EncryptedFileSecretStore(data, new byte[32], "fixture", "key-one"); }
    private Path path(String ref) { String[] p=ref.split(":"); return data.resolve(".settings/secrets/"+p[2]+"-"+p[3]+".secret"); }
    private byte[] nonce(String ref) throws Exception {
        try (var in=new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(path(ref))))) {
            in.readInt(); in.readByte(); in.readUTF(); in.readUTF(); return in.readNBytes(12);
        }
    }
    @Test void roundTripRandomNonceAndNoReversiblePlaintextOnDisk() throws Exception {
        var store=store(); String value="test-only-credential-canary-甲";
        String a=store.put("qwenApiKey",value), b=store.put("qwenApiKey",value);
        assertNotEquals(a,b); assertFalse(Arrays.equals(nonce(a),nonce(b)));
        assertEquals(value,store.resolve(a)); assertEquals(value,store().resolve(b));
        for (String ref:List.of(a,b)) {
            String disk=new String(Files.readAllBytes(path(ref)),StandardCharsets.ISO_8859_1);
            assertFalse(disk.contains("test-only-credential-canary"));
            assertFalse(disk.contains(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
        }
        assertFalse(store.toString().contains(value));
    }
    @Test void wrongMasterDeploymentAndKeyIdentityFailClosedWithoutCauses() {
        String ref=store().put("qwenApiKey","test-only-original"); byte[] wrong=new byte[32]; wrong[0]=1;
        for (var reader:List.of(new EncryptedFileSecretStore(data,wrong,"fixture","key-one"),
                new EncryptedFileSecretStore(data,new byte[32],"other","key-one"),
                new EncryptedFileSecretStore(data,new byte[32],"fixture","other"))) {
            var error=assertThrows(SecretStore.Failure.class,()->reader.resolve(ref));
            assertNull(error.getCause()); assertFalse(error.getMessage().contains("test-only-original"));
        }
        assertEquals("test-only-original",store().resolve(ref));
    }
    @Test void authenticatedCiphertextAndReferenceCannotBeSwapped() throws Exception {
        var s=store(); String a=s.put("qwenApiKey","canary-one"),b=s.put("jevApiKey","canary-two");
        byte[] bytes=Files.readAllBytes(path(a)); Files.write(path(b),bytes);
        assertThrows(SecretStore.Failure.class,()->s.resolve(b));
        bytes[bytes.length-1]^=1; Files.write(path(a),bytes);
        assertThrows(SecretStore.Failure.class,()->s.resolve(a));
    }
    @Test void rejectsTraversalSymlinkAndUnsafePermissions() throws Exception {
        var s=store(); assertThrows(SecretStore.Failure.class,()->s.put("../escape","canary"));
        assertThrows(SecretStore.Failure.class,()->s.resolve("enc:v1:qwenApiKey:../../outside"));
        String ref=s.put("qwenApiKey","canary"); Path p=path(ref); Path outside=data.resolve("outside");
        Files.copy(p,outside); Files.delete(p); Files.createSymbolicLink(p,outside);
        assertThrows(SecretStore.Failure.class,()->s.resolve(ref));
        Files.delete(p); Files.copy(outside,p);
        Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(SecretStore.Failure.class,()->s.resolve(ref));
    }
    @Test void retentionPreservesActiveRefsAndBoundsAbandonedRecords() throws Exception {
        var s=store(); String active=s.put("qwenApiKey","active-canary");
        for(int i=1;i<64;i++) s.put("jevApiKey","orphan-canary-"+i);
        assertThrows(SecretStore.Failure.class,()->s.put("jevApiKey","overflow-canary"));
        s.retain(Set.of(active));
        try(var files=Files.list(data.resolve(".settings/secrets"))) { assertEquals(1,files.count()); }
        assertEquals("active-canary",s.resolve(active)); assertNotNull(s.put("jevApiKey","next-canary"));
    }
    @Test void environmentModeIsReadOnlyAndNeverGeneratesMasterKey() {
        var env=SecretStore.fromEnvironment(data,Map.of("qwenApiKey","injected-canary"),Map.of());
        assertEquals(SecretStore.Mode.ENV_ONLY,env.mode()); assertFalse(env.writable());
        String ref=env.put("qwenApiKey","injected-canary"); assertEquals("injected-canary",env.resolve(ref));
        assertThrows(SecretStore.Failure.class,()->env.put("qwenApiKey","replacement-canary"));
        assertFalse(Files.exists(data.resolve(".settings")));
        assertThrows(SecretStore.Failure.class,()->SecretStore.fromEnvironment(data,Map.of(),Map.of("BOOK_SECRET_STORE_MODE","ENCRYPTED_FILE")));
        assertThrows(SecretStore.Failure.class,()->SecretStore.fromEnvironment(data,Map.of(),Map.of("BOOK_SECRET_STORE_MODE","PLAINTEXT")));
    }
    @Test void keyInputIsCopiedAndMaximumCredentialRoundTrips() {
        byte[] key=new byte[32]; var s=new EncryptedFileSecretStore(data,key,"fixture","key-one");
        Arrays.fill(key,(byte)42); String value="甲".repeat(4096);
        String ref=s.put("qwenApiKey",value); assertEquals(value,store().resolve(ref));
        assertThrows(SecretStore.Failure.class,()->s.put("qwenApiKey",value+"甲"));
    }
}
