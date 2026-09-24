package studio.bookhtml.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.CloudConsent;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Canonical consent records are authority; per-book copies are never independent grants. */
@Component
public class CloudConsentStore {
    private static final Object[] LOCKS=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private static final int MAX_RECORDS=4096,MAX_BYTES=256*1024;
    private final Path dataDir;
    private final ObjectMapper json;
    private final Object lock;
    @org.springframework.beans.factory.annotation.Autowired
    public CloudConsentStore(AppProperties properties,ObjectMapper json) { this(properties.dataDir(),json); }
    public CloudConsentStore(Path dataDir,ObjectMapper json) {
        Path resolved=Objects.requireNonNull(dataDir).toAbsolutePath().normalize();
        try { Files.createDirectories(resolved);resolved=resolved.toRealPath(); }
        catch(IOException ignored) { /* Subsequent authoritative reads/writes fail closed. */ }
        this.dataDir=resolved;this.lock=LOCKS[Math.floorMod(resolved.hashCode(),LOCKS.length)];
        this.json=Objects.requireNonNull(json).copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
    private Path consentsDir(){return dataDir.resolve("consents");}
    private Path consentPath(UUID id){return consentsDir().resolve(id+".json");}
    private Path defaultMarker(String subject) {
        return dataDir.resolve("consent-initialization").resolve(studio.bookhtml.decision.DecisionHash.sha256Hex(subject)+".json");
    }
    /** Consume the one-time initializer before granting. Crashes can withhold a default, never recreate a revoked grant. */
    public boolean claimDefaultInitialization(String subject) throws IOException {
        Objects.requireNonNull(subject);
        synchronized(lock) {
            Path marker=defaultMarker(subject);DurableJson.rejectLinks(marker);
            if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)) return false;
            boolean hasHistory=records().stream().anyMatch(c->subject.equals(c.subjectId()));
            DurableJson.write(marker,Map.of("schemaVersion",1,"subjectId",subject,"initializedAt",Instant.now().toString()),json,4096);
            return !hasHistory;
        }
    }
    private CloudConsent checkedRead(Path path,UUID expected) throws IOException {
        DurableJson.rejectLinks(path);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("consent record unavailable");
        byte[] data;
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){data=input.readNBytes(MAX_BYTES+1);}
        if(data.length>MAX_BYTES)throw new IOException("consent record exceeds bound");
        try(var parser=json.getFactory().createParser(data)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            CloudConsent c=json.readValue(parser,CloudConsent.class);
            if(parser.nextToken()!=null||c==null||!expected.equals(c.consentId())||c.schemaVersion()!=1
                    ||c.subjectId().isBlank()||c.subjectId().length()>200||c.policyRevision()<1
                    ||c.allowedProviders().size()>64||c.allowedProviders().stream().anyMatch(v->v==null||v.isBlank()||v.length()>100))
                throw new IOException("consent identity invalid");
            return c;
        }catch(RuntimeException invalid){throw new IOException("consent record invalid");}
    }
    private List<CloudConsent> records() throws IOException {
        Path dir=consentsDir();DurableJson.rejectLinks(dir);
        if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return List.of();
        List<Path> paths;
        try(var stream=Files.list(dir)){paths=stream.filter(p->p.getFileName().toString().endsWith(".json")).limit(MAX_RECORDS+1).sorted().toList();}
        if(paths.size()>MAX_RECORDS)throw new IOException("consent capacity requires maintenance");
        List<CloudConsent> result=new ArrayList<>();
        for(Path p:paths) {
            String name=p.getFileName().toString();UUID id;
            try{id=UUID.fromString(name.substring(0,name.length()-5));if(!name.equals(id+".json"))throw new IllegalArgumentException();}
            catch(IllegalArgumentException bad){throw new IOException("consent filename invalid");}
            result.add(checkedRead(p,id));
        }
        result.sort(Comparator.comparing(CloudConsent::issuedAt).reversed().thenComparing(c->c.consentId().toString()));
        return List.copyOf(result);
    }
    private static ApiException unavailable(){return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"云端授权记录无法验证，未新增或恢复授权；请检查私有数据备份");}
    public CloudConsent write(CloudConsent consent) throws IOException {
        Objects.requireNonNull(consent);
        synchronized(lock) {
            if(consent.schemaVersion()!=1||consent.policyRevision()<1)throw new IOException("invalid consent");
            DurableJson.rejectLinks(consentsDir());Files.createDirectories(consentsDir());
            // One authoritative write; stale per-book shortcuts cannot grant access after revocation.
            DurableJson.write(consentPath(consent.consentId()),consent,json,MAX_BYTES);
            return consent;
        }
    }
    public CloudConsent read(UUID id) {
        if(id==null)return null;
        synchronized(lock) {
            try { Path p=consentPath(id);DurableJson.rejectLinks(p);
                return Files.exists(p,LinkOption.NOFOLLOW_LINKS)?checkedRead(p,id):null;
            }catch(IOException invalid){throw unavailable();}
        }
    }
    public CloudConsent findActiveConsent(String subject,String book) {
        Objects.requireNonNull(subject);
        synchronized(lock) {
            try {
                var matching=records().stream().filter(c->subject.equals(c.subjectId())&&c.permitsBook(book)).toList();
                return matching.stream().filter(c->"BOOK".equals(c.scope().kind())).findFirst()
                        .orElseGet(()->matching.stream().findFirst().orElse(null));
            }catch(IOException invalid){throw unavailable();}
        }
    }
    public List<CloudConsent> listForSubject(String subject) {
        Objects.requireNonNull(subject);
        synchronized(lock) {
            try{return records().stream().filter(c->subject.equals(c.subjectId())).toList();}
            catch(IOException invalid){throw unavailable();}
        }
    }
    public CloudConsent revoke(UUID id,Instant now)throws IOException {
        Objects.requireNonNull(id);Objects.requireNonNull(now);
        synchronized(lock) {
            CloudConsent current=read(id);if(current==null||current.revokedAt()!=null)return current;
            return write(current.withRevocation(now));
        }
    }
}
