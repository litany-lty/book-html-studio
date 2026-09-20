package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@Service
public class PaddleOcrClient {
    private static final URI AUTH_URI=URI.create("https://aip.baidubce.com/oauth/2.0/token");
    private static final int MAX_FORM_BYTES=10*1024*1024,MAX_API_BYTES=32*1024*1024,MAX_RESULT_BYTES=64*1024*1024;
    /** 可降级的额度/限流/权限错误码（百度官方错误码表）。 */
    private static final Set<String> QUOTA_CODES=Set.of("4","6","17","18","19","216604");
    private final AppProperties config;private final ObjectMapper json;private final PaddleOcrParser parser;private final Transport transport;private final Waiter waiter;private final Map<String,Object>locks=new ConcurrentHashMap<>();
    private String accessToken="";private long tokenExpiresAt;
    @Autowired public PaddleOcrClient(AppProperties config,ObjectMapper json,PaddleOcrParser parser){this(config,json,parser,new JdkTransport(),PaddleOcrClient::waitCancellable);}
    PaddleOcrClient(AppProperties config,ObjectMapper json,PaddleOcrParser parser,Transport transport,Waiter waiter){this.config=config;this.json=json;this.parser=parser;this.transport=transport;this.waiter=waiter;}
    public boolean configured(){return !config.baiduOcrApiKey().isBlank()&&!config.baiduOcrSecretKey().isBlank();}
    public List<Block>recognize(byte[]png,int width,int height,String layout,BooleanSupplier cancelled)throws OcrException{
        if(!configured())throw new ApiException(HttpStatus.BAD_REQUEST,"PaddleOCR-VL 尚未配置百度 OCR API Key 与 Secret Key");
        if(png.length==0||png.length>10*1024*1024)throw new ApiException(HttpStatus.BAD_REQUEST,"送识 PNG 超过 PaddleOCR-VL 10MB 限制");
        String hash=fingerprint(png);Object lock=locks.computeIfAbsent(hash,key->new Object());
        synchronized(lock){try{return recognizeLocked(hash,png,width,height,layout,cancelled);}finally{locks.remove(hash,lock);}}
    }
    private List<Block>recognizeLocked(String hash,byte[]png,int width,int height,String layout,BooleanSupplier cancelled)throws OcrException{
        CacheEntry cache=readCacheCompatible(hash,sha256(png));
        if(cache!=null&&cache.result()!=null&&!cache.result().isNull())return parser.parse(cache.result(),width,height,layout);
        if(cache!=null&&"failed".equals(cache.state()))throw new OcrException("PaddleOCR-VL 远端任务已失败；为避免重复计费未自动重提");
        String taskId=cache==null?null:cache.taskId();
        if(taskId==null||taskId.isBlank()){if(cache!=null)throw new OcrException("PaddleOCR-VL 提交结果不确定；为避免重复计费未自动重提");checkCancelled(cancelled);token(cancelled);writeCache(hash,new CacheEntry(hash,null,"submitting",null));try{taskId=submit(png,cancelled);writeCache(hash,new CacheEntry(hash,taskId,"submitted",null));}catch(CancelledException e){writeCache(hash,new CacheEntry(hash,null,"submit-unknown",null));throw e;}catch(QuotaExceededException e){try{Files.deleteIfExists(cachePath(hash));}catch(Exception ignored){}throw e;}catch(Exception e){writeCache(hash,new CacheEntry(hash,null,"submit-unknown",null));throw new OcrException("PaddleOCR-VL 提交结果不确定；为避免重复计费未自动重提");}}
        long deadline=System.nanoTime()+Duration.ofSeconds(config.paddleTotalTimeoutSeconds()).toNanos();String state=cache==null?"submitted":cache.state();
        while(System.nanoTime()<deadline){checkCancelled(cancelled);waiter.pause(config.paddlePollIntervalSeconds(),cancelled);checkCancelled(cancelled);JsonNode response=postForm(jobUri("query"),Map.of("task_id",taskId),cancelled,MAX_API_BYTES);ensureSuccess(response,"PaddleOCR-VL 任务查询失败");JsonNode result=response.path("result");state=result.path("status").asText("").toLowerCase(Locale.ROOT);writeCache(hash,new CacheEntry(hash,taskId,state,null));
            if(Set.of("pending","processing","running").contains(state))continue;
            if("failed".equals(state))throw new OcrException("PaddleOCR-VL 远端任务失败；为避免重复计费未自动重提");
            if(!Set.of("success","done").contains(state))throw new OcrException("PaddleOCR-VL 返回未知任务状态");
            URI resultUri=validateResultUri(result.path("parse_result_url").asText(""));JsonNode downloaded=getResult(resultUri,cancelled);JsonNode sanitized=sanitize(downloaded,"");List<Block>blocks=parser.parse(sanitized,width,height,layout);writeCache(hash,new CacheEntry(hash,taskId,"done",sanitized));return blocks;
        }
        writeCache(hash,new CacheEntry(hash,taskId,state,null));throw new OcrException("PaddleOCR-VL 任务仍在远端处理中，可稍后重试继续查询（远端任务可能仍计费）");
    }
    private String submit(byte[]png,BooleanSupplier cancelled)throws OcrException{Map<String,String>form=new LinkedHashMap<>();form.put("file_data",Base64.getEncoder().encodeToString(png));form.put("file_name","page.png");form.put("analysis_chart","false");form.put("return_span_boxes","true");form.put("angle_adjust","false");form.put("unwarp","false");JsonNode response=postForm(jobUri(""),form,cancelled,MAX_API_BYTES);ensureSuccess(response,"PaddleOCR-VL 提交失败");String taskId=response.path("result").path("task_id").asText("");if(taskId.isBlank()||taskId.length()>200)throw new OcrException("PaddleOCR-VL 提交响应缺少 task_id");return taskId;}
    private JsonNode postForm(URI uri,Map<String,String>values,BooleanSupplier cancelled,int maxBytes)throws OcrException{String body=form(values);if(body.getBytes(StandardCharsets.UTF_8).length>MAX_FORM_BYTES)throw new ApiException(HttpStatus.BAD_REQUEST,"PaddleOCR-VL Base64 表单超过 10MB 限制");String token=token(cancelled);URI target=withToken(uri,token);HttpRequest request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(config.paddleRequestTimeoutSeconds())).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();return sendJson(request,cancelled,maxBytes,"PaddleOCR-VL 请求失败");}
    private synchronized String token(BooleanSupplier cancelled)throws OcrException{long now=System.currentTimeMillis();if(!accessToken.isBlank()&&now<tokenExpiresAt)return accessToken;Map<String,String>query=new LinkedHashMap<>();query.put("grant_type","client_credentials");query.put("client_id",config.baiduOcrApiKey());query.put("client_secret",config.baiduOcrSecretKey());HttpRequest request=HttpRequest.newBuilder(URI.create(AUTH_URI+"?"+form(query))).timeout(Duration.ofSeconds(Math.min(30,config.paddleRequestTimeoutSeconds()))).POST(HttpRequest.BodyPublishers.noBody()).build();JsonNode response=sendJson(request,cancelled,MAX_API_BYTES,"百度 OCR 认证失败");String token=response.path("access_token").asText("");if(token.isBlank())throw new OcrException("百度 OCR 认证失败");long expires=Math.max(60,response.path("expires_in").asLong(3600));accessToken=token;tokenExpiresAt=now+Math.max(30,expires-60)*1000;return accessToken;}
    private JsonNode getResult(URI uri,BooleanSupplier cancelled)throws OcrException{HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(config.paddleRequestTimeoutSeconds())).GET().build();return sendJson(request,cancelled,MAX_RESULT_BYTES,"PaddleOCR-VL 结果下载失败");}
    private JsonNode sendJson(HttpRequest request,BooleanSupplier cancelled,int maxBytes,String safeMessage)throws OcrException{try{checkCancelled(cancelled);Response response=transport.send(request,cancelled,maxBytes);if(response.status()==429)throw new QuotaExceededException(safeMessage+"（HTTP 429 限流）");if(response.status()>=300&&response.status()<400)throw new OcrException(safeMessage+"（已拒绝重定向）");if(response.status()<200||response.status()>=300)throw new OcrException(safeMessage+"（HTTP "+response.status()+"）");return json.readTree(response.body());}catch(CancelledException|OcrException|ApiException e){throw e;}catch(Exception e){throw new OcrException(safeMessage,e);}}
    private void ensureSuccess(JsonNode node,String message)throws OcrException{String code=errorCode(node);if(code==null)return;if(QUOTA_CODES.contains(code))throw new QuotaExceededException(message+"（远端额度/权限不足，错误码 "+code+"）");throw new OcrException(message+"（错误码 "+code+"）");}
    private static String errorCode(JsonNode node){JsonNode code=node==null?null:node.get("error_code");if(code==null||code.isNull())return null;if(code.isNumber()&&code.asInt()==0)return null;if(code.isTextual()&&("0".equals(code.asText())||code.asText().isBlank()))return null;return code.isTextual()?code.asText().strip():String.valueOf(code.asInt());}
    private URI jobUri(String suffix)throws OcrException{try{URI base=URI.create(config.paddleJobUrl().replaceAll("/+$",""));if(!"https".equalsIgnoreCase(base.getScheme())||!"aip.baidubce.com".equalsIgnoreCase(base.getHost())||base.getUserInfo()!=null||(base.getPort()!=-1&&base.getPort()!=443)||base.getQuery()!=null||base.getFragment()!=null)throw new IllegalArgumentException();return suffix.isEmpty()?base:URI.create(base+"/"+suffix);}catch(Exception e){throw new OcrException("PaddleOCR-VL 任务地址配置无效");}}
    private static URI withToken(URI uri,String token)throws OcrException{try{return new URI(uri.getScheme(),uri.getAuthority(),uri.getPath(),(uri.getQuery()==null?"":uri.getQuery()+"&")+"access_token="+URLEncoder.encode(token,StandardCharsets.UTF_8),null);}catch(Exception e){throw new OcrException("PaddleOCR-VL 请求地址无效");}}
    static URI validateResultUri(String raw)throws OcrException{try{URI uri=URI.create(raw);String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||isIpLiteral(host)||!(host.endsWith(".bcebos.com")||host.endsWith(".baidubce.com")))throw new IllegalArgumentException();return uri;}catch(Exception e){throw new OcrException("PaddleOCR-VL 结果地址未通过安全校验");}}
    private static boolean isIpLiteral(String host){if(host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}"))return true;return host.contains(":");}
    private static final String FINGERPRINT_VERSION = "paddle-v1\u0000";
    private Path cachePath(String hash){return config.dataDir().resolve("cache").resolve("paddle").resolve(hash+".json");}
    private CacheEntry readCache(String hash)throws OcrException{Path path=cachePath(hash);if(!Files.exists(path))return null;try{CacheEntry entry=json.readValue(path.toFile(),CacheEntry.class);if(!hash.equals(entry.inputHash()))throw new IOException();return entry;}catch(Exception e){throw new OcrException("PaddleOCR-VL 本地任务缓存损坏",e);}}
    /** 阶段3：旧缓存显式兼容——新指纹未命中时尝试纯图片哈希的旧文件，命中则迁移到新指纹，不一律失效重复计费。 */
    CacheEntry readCacheCompatible(String fingerprint,String legacyHash)throws OcrException{
        CacheEntry fresh=readCache(fingerprint);
        if(fresh!=null) return fresh;
        if(legacyHash==null||legacyHash.equals(fingerprint)) return null;
        Path legacy=cachePath(legacyHash);
        if(!Files.exists(legacy)) return null;
        try{
            CacheEntry old=json.readValue(legacy.toFile(),CacheEntry.class);
            if(!legacyHash.equals(old.inputHash())) throw new java.io.IOException();
            CacheEntry migrated=new CacheEntry(fingerprint,old.taskId(),old.state(),old.result());
            try{writeCache(fingerprint,migrated);}catch(Exception ignored){}
            return migrated;
        }catch(Exception e){throw new OcrException("PaddleOCR-VL 本地任务缓存损坏",e);}
    }
    String fingerprint(byte[] png)throws OcrException{
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update(FINGERPRINT_VERSION.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            String model=config.paddleModel()==null?"":config.paddleModel();
            digest.update(model.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            digest.update(png);
            return HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){throw new OcrException("无法计算送识图片摘要",e);}
    }
    static String legacyHash(byte[] png)throws OcrException{return sha256(png);}
    private void writeCache(String hash,CacheEntry entry)throws OcrException{Path path=cachePath(hash),tmp=path.resolveSibling(path.getFileName()+".tmp");try{Files.createDirectories(path.getParent());setPrivate(path.getParent(),true);json.writeValue(tmp.toFile(),entry);setPrivate(tmp,false);try{Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}setPrivate(path,false);}catch(Exception e){throw new OcrException("PaddleOCR-VL 本地任务缓存保存失败",e);}}
    private static void setPrivate(Path path,boolean directory){try{Files.setPosixFilePermissions(path,directory?Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE):Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));}catch(UnsupportedOperationException|IOException ignored){}}
    private JsonNode sanitize(JsonNode value,String key){if(value==null)return json.nullNode();String lower=key.toLowerCase(Locale.ROOT);if(lower.contains("token")||lower.contains("secret")||lower.equals("authorization")||lower.equals("parse_result_url")||lower.equals("markdown_url"))return json.getNodeFactory().textNode("[REDACTED]");if(value.isObject()){var out=json.createObjectNode();value.fields().forEachRemaining(e->out.set(e.getKey(),sanitize(e.getValue(),e.getKey())));return out;}if(value.isArray()){var out=json.createArrayNode();value.forEach(v->out.add(sanitize(v,key)));return out;}if(value.isTextual())return json.getNodeFactory().textNode(stripUrlQuery(value.asText()));return value.deepCopy();}
    private static String stripUrlQuery(String value){return value.replaceAll("(https?://[^\\s\\\"'<>?#]+)[?#][^\\s\\\"'<>]*","$1");}
    private static String form(Map<String,String>values){StringJoiner out=new StringJoiner("&");values.forEach((k,v)->out.add(URLEncoder.encode(k,StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,StandardCharsets.UTF_8)));return out.toString();}
    private static String sha256(byte[]bytes)throws OcrException{try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new OcrException("无法计算送识图片摘要",e);}}
    private static void checkCancelled(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancelledException();}
    private static void waitCancellable(int seconds,BooleanSupplier cancelled){long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(System.nanoTime()<end){checkCancelled(cancelled);try{Thread.sleep(Math.min(200,Math.max(1,Duration.ofNanos(end-System.nanoTime()).toMillis())));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancelledException();}}}
    static Response signedGet(URI uri,Duration timeout,BooleanSupplier cancelled,int maxBytes)throws Exception{return signedGet(uri,timeout,cancelled,maxBytes,null);}
    static Response signedGet(URI uri,Duration timeout,BooleanSupplier cancelled,int maxBytes,Proxy explicitProxy)throws Exception{long deadline=System.nanoTime()+timeout.toNanos();AtomicReference<HttpURLConnection>active=new AtomicReference<>();CompletableFuture<Response>future=CompletableFuture.supplyAsync(()->{HttpURLConnection connection=null;try{connection=(HttpURLConnection)(explicitProxy==null?uri.toURL().openConnection():uri.toURL().openConnection(explicitProxy));active.set(connection);int millis=(int)Math.min(Integer.MAX_VALUE,Math.max(1,timeout.toMillis()));connection.setConnectTimeout(millis);connection.setReadTimeout(millis);connection.setInstanceFollowRedirects(false);connection.setRequestMethod("GET");int status=connection.getResponseCode();InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in!=null){byte[]buffer=new byte[8192];int total=0,read;while((read=in.read(buffer))!=-1){checkCancelled(cancelled);total+=read;if(total>maxBytes)throw new OcrException("PaddleOCR-VL 响应超过安全大小限制");out.write(buffer,0,read);}}return new Response(status,out.toString(StandardCharsets.UTF_8));}}catch(Exception e){throw new CompletionException(e);}finally{active.set(null);if(connection!=null)connection.disconnect();}});while(true){try{checkCancelled(cancelled);long remaining=deadline-System.nanoTime();if(remaining<=0){future.cancel(true);HttpURLConnection connection=active.get();if(connection!=null)connection.disconnect();throw new OcrException("PaddleOCR-VL 网络响应超时");}return future.get(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining)+1,200),TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}catch(CancelledException e){future.cancel(true);HttpURLConnection connection=active.get();if(connection!=null)connection.disconnect();throw e;}catch(InterruptedException e){future.cancel(true);HttpURLConnection connection=active.get();if(connection!=null)connection.disconnect();Thread.currentThread().interrupt();throw new CancelledException();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();if(cause instanceof OcrException ocr)throw ocr;throw new IOException("PaddleOCR-VL 网络读写失败");}}}
    record CacheEntry(String inputHash,String taskId,String state,JsonNode result){}
    record Response(int status,String body){}
    @FunctionalInterface interface Transport{Response send(HttpRequest request,BooleanSupplier cancelled,int maxBytes)throws Exception;}
    @FunctionalInterface interface Waiter{void pause(int seconds,BooleanSupplier cancelled);}
    private static final class JdkTransport implements Transport{
        private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        public Response send(HttpRequest request,BooleanSupplier cancelled,int maxBytes)throws Exception{if("GET".equals(request.method()))return signedGet(request.uri(),request.timeout().orElse(Duration.ofSeconds(60)),cancelled,maxBytes);long deadline=System.nanoTime()+request.timeout().orElse(Duration.ofSeconds(60)).toNanos();CompletableFuture<HttpResponse<InputStream>>future=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());HttpResponse<InputStream>response=await(future,null,deadline,cancelled);InputStream stream=response.body();CompletableFuture<String>body=CompletableFuture.supplyAsync(()->read(stream,maxBytes,cancelled));try{return new Response(response.statusCode(),await(body,stream,deadline,cancelled));}finally{close(stream);}}
        private static String read(InputStream in,int maxBytes,BooleanSupplier cancelled){try(in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]buffer=new byte[8192];int total=0,read;while((read=in.read(buffer))!=-1){checkCancelled(cancelled);total+=read;if(total>maxBytes)throw new CompletionException(new OcrException("PaddleOCR-VL 响应超过安全大小限制"));out.write(buffer,0,read);}return out.toString(StandardCharsets.UTF_8);}catch(IOException e){throw new CompletionException(e);}}
        private static <T>T await(CompletableFuture<T>future,InputStream stream,long deadline,BooleanSupplier cancelled)throws Exception{while(true){try{checkCancelled(cancelled);long remaining=deadline-System.nanoTime();if(remaining<=0){future.cancel(true);close(stream);throw new OcrException("PaddleOCR-VL 网络响应超时");}return future.get(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining)+1,200),TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}catch(InterruptedException e){future.cancel(true);close(stream);Thread.currentThread().interrupt();throw new CancelledException();}catch(CancelledException e){future.cancel(true);close(stream);throw e;}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();if(cause instanceof OcrException ocr)throw ocr;throw new IOException("PaddleOCR-VL 网络读写失败");}}}
        private static void close(InputStream stream){if(stream!=null)try{stream.close();}catch(IOException ignored){}}
    }
}
