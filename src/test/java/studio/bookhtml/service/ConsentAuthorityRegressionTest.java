package studio.bookhtml.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConsentAuthorityRegressionTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    BookStore store; CloudConsentService service;
    @BeforeEach void setup() throws Exception {
        store=new BookStore(TestConfigs.config(temp,"",""),json);
        service=new CloudConsentService(store.consentStore(),store.policyStore(),store.epochStore());
    }
    @AfterEach void close(){store.close();}
    @Test void repeatedInitializerKeepsExactlyOneDefault() {
        service.init();var initial=service.findActiveConsent(null,null);
        service.init();service.ensureDefaultConsent();
        assertEquals(initial.consentId(),service.findActiveConsent(null,null).consentId());
        assertEquals(1,store.consentStore().listForSubject("local-owner").size());
    }
    @Test void legacyRevocationWithoutInitializerMarkerIsStillFinal() throws Exception {
        service.init();var initial=service.findActiveConsent(null,null);
        service.revokeConsent(null,initial.consentId(),null,"revoke");
        try(var files=Files.list(temp.resolve("consent-initialization"))) {
            for(Path p:files.toList())Files.delete(p);
        }
        service.init();assertNull(service.findActiveConsent(null,null));
        assertEquals(1,store.consentStore().listForSubject("local-owner").size());
    }
    @Test void staleBookShortcutCannotOverrideRevokedCanonicalGrant() throws Exception {
        service.init();var initial=service.findActiveConsent(null,null);
        String book=UUID.randomUUID().toString();store.createBookDirectory(book);
        Path shortcut=store.bookDir(book).resolve("cloud-consent.json");
        Files.write(shortcut,json.writeValueAsBytes(initial));byte[] before=Files.readAllBytes(shortcut);
        service.revokeConsent(null,initial.consentId(),null,"revoke");
        assertNull(service.findActiveConsent(null,book));
        assertArrayEquals(before,Files.readAllBytes(shortcut));
        assertFalse(new CloudConsentService(new CloudConsentStore(temp,json),store.policyStore(),store.epochStore()).isCloudAuthorized(book,"qwen"));
    }
    @Test void removingCanonicalRecordDoesNotPromoteBookCopy() throws Exception {
        service.init();var grant=service.findActiveConsent(null,null);
        String book=UUID.randomUUID().toString();store.createBookDirectory(book);
        Files.write(store.bookDir(book).resolve("cloud-consent.json"),json.writeValueAsBytes(grant));
        Files.delete(temp.resolve("consents").resolve(grant.consentId()+".json"));
        service.init();assertNull(service.findActiveConsent(null,book));
    }
    @Test void corruptionDoesNotBootstrapAReplacementGrant() throws Exception {
        Path dir=temp.resolve("consents");Files.createDirectory(dir);
        Path file=dir.resolve(UUID.randomUUID()+".json");Files.writeString(file,"{damaged");
        service.init();assertThrows(Exception.class,()->service.findActiveConsent(null,null));
        assertEquals("{damaged",Files.readString(file));
    }
    @Test void expiryDoesNotTurnIntoAPermanentGrant() throws Exception {
        service.init();var grant=service.findActiveConsent(null,null);
        Path file=temp.resolve("consents").resolve(grant.consentId()+".json");
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(file));
        value.put("expiresAt",Instant.now().minusSeconds(60).toString());
        Files.write(file,json.writeValueAsBytes(value));
        service.init();assertNull(service.findActiveConsent(null,null));
    }
    @Test void manualPolicyIsNotSilentlyChangedByInitialization() throws Exception {
        var policy=service.getReadingPolicy(null);
        service.updateReadingPolicy(null,new studio.bookhtml.api.ReadingPolicyUpdateRequest(policy.policyRevision(),new ReadingPolicy.WindowConfig(1,2,"MANUAL")));
        service.init();assertNull(service.findActiveConsent(null,null));
        assertEquals("MANUAL",service.getReadingPolicy(null).defaultWindow().mode());
    }
    @Test void anotherStoreHandleReadsTheLatestRevocation() throws Exception {
        service.init();var grant=service.findActiveConsent(null,null);
        var second=new CloudConsentStore(temp,json);
        assertTrue(second.read(grant.consentId()).isValid());
        store.consentStore().revoke(grant.consentId(),Instant.now());
        assertFalse(second.read(grant.consentId()).isValid());
    }
}
