package studio.bookhtml.config;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Dependency-free local smoke; does not replace SettingsPersistence/JUnit/Maven regressions. */
public final class SecretStoreProbe {
    private static int checks;
    private static void check(boolean ok) { if (!ok) throw new AssertionError("secret-store assertion " + (checks+1)); checks++; }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("expected fail-closed"); }
        catch (SecretStore.Failure error) { check(error.getCause()==null && !error.getMessage().contains("canary")); }
    }
    private static Path path(Path data,String ref) { String[] p=ref.split(":"); return data.resolve(".settings/secrets/"+p[2]+"-"+p[3]+".secret"); }
    private static byte[] nonce(byte[] record) throws Exception {
        try(var in=new DataInputStream(new ByteArrayInputStream(record))) { in.readInt();in.readByte();in.readUTF();in.readUTF();return in.readNBytes(12); }
    }
    public static void main(String[] args) throws Exception {
        Path data=Files.createTempDirectory("secret-store-probe-");
        try {
            byte[] key=new byte[32]; var s=new EncryptedFileSecretStore(data,key,"fixture","fixture-key");
            String value="test-only-canary-甲"; String a=s.put("qwenApiKey",value),b=s.put("qwenApiKey",value);
            check(!a.equals(b)); check(s.resolve(a).equals(value));
            byte[] bytesA=Files.readAllBytes(path(data,a)),bytesB=Files.readAllBytes(path(data,b));
            check(!Arrays.equals(nonce(bytesA),nonce(bytesB)));
            check(!new String(bytesA,StandardCharsets.ISO_8859_1).contains("test-only-canary"));
            check(!new String(bytesA,StandardCharsets.ISO_8859_1).contains(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
            check(!s.toString().contains(value));
            check(new EncryptedFileSecretStore(data,key,"fixture","fixture-key").resolve(a).equals(value));
            key[0]=1; rejects(()->new EncryptedFileSecretStore(data,key,"fixture","fixture-key").resolve(a));
            rejects(()->new EncryptedFileSecretStore(data,new byte[32],"other","fixture-key").resolve(a));
            rejects(()->new EncryptedFileSecretStore(data,new byte[32],"fixture","other").resolve(a));
            check(s.resolve(a).equals(value));
            Files.write(path(data,b),bytesA); rejects(()->s.resolve(b));
            byte[] altered=bytesA.clone();altered[altered.length-1]^=1;Files.write(path(data,a),altered);rejects(()->s.resolve(a));
            Files.write(path(data,a),bytesA); s.retain(Set.of(a));check(!Files.exists(path(data,b)));check(s.resolve(a).equals(value));
            rejects(()->s.resolve("enc:v1:qwenApiKey:../../elsewhere")); rejects(()->s.put("../invalid","canary"));
            String max="甲".repeat(4096);String c=s.put("jevApiKey",max);check(s.resolve(c).equals(max));rejects(()->s.put("jevApiKey",max+"甲"));
            var env=SecretStore.fromEnvironment(data,Map.of("qwenApiKey",value),Map.of());
            check(!env.writable());check(env.resolve(env.put("qwenApiKey",value)).equals(value));rejects(()->env.put("qwenApiKey","replacement-canary"));
            rejects(()->SecretStore.fromEnvironment(data,Map.of(),Map.of("BOOK_SECRET_STORE_MODE","ENCRYPTED_FILE")));
            rejects(()->SecretStore.fromEnvironment(data,Map.of(),Map.of("BOOK_SECRET_STORE_MODE","PLAINTEXT")));
            Path target=path(data,a);Files.delete(target);Files.createSymbolicLink(target,path(data,c));rejects(()->s.resolve(a));
            Files.delete(target);s.retain(Set.of(c));check(s.resolve(c).equals(max));
            for(int i=1;i<64;i++)s.put("qwenApiKey","capacity-canary-"+i);
            rejects(()->s.put("qwenApiKey","overflow-canary"));s.retain(Set.of(c));check(s.put("qwenApiKey","next-canary")!=null);
            System.out.println("{\"suite\":\"secret-store-core\",\"checks\":"+checks+",\"failures\":0,\"cloudCalls\":0}");
        } finally {
            try(var paths=Files.walk(data)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p); }
        }
    }
}
