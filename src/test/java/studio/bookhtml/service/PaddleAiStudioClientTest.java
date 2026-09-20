package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.domain.Block;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PaddleAiStudioClientTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void submitsMultipartPollsDownloadsJsonLinesAndCachesNormalizedCoordinates() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(List.of(
                response(200,"{\"code\":0,\"data\":{\"jobId\":\"job-1\"}}"),
                response(200,"{\"code\":0,\"data\":{\"state\":\"running\"}}"),
                response(200,"{\"code\":0,\"data\":{\"state\":\"done\",\"resultUrl\":{\"jsonUrl\":\"https://bucket.bcebos.com/result.jsonl?authorization=signed-secret\"}}}"),
                response(200,jsonLine())));
        PaddleAiStudioClient client = client(properties("secret-token",5,5,1),transport,(seconds,cancelled)->{});

        List<Block> blocks = client.recognize(png(),1000,800,"auto",()->false);

        assertEquals(2,blocks.size());
        assertEquals("table",blocks.get(0).type());
        assertEquals("paddle-aistudio-t",blocks.get(0).id());
        assertEquals("paddle-aistudio",blocks.get(0).source());
        assertArrayEquals(new double[]{.1,.125,.3,.25},blocks.get(0).bbox(),1e-9);
        assertEquals(4,transport.requests.size());
        HttpRequest submit=transport.requests.get(0),download=transport.requests.get(3);
        assertEquals("POST",submit.method());
        assertEquals("Bearer secret-token",submit.headers().firstValue("Authorization").orElseThrow());
        String multipart=new String(body(submit),StandardCharsets.ISO_8859_1);
        assertTrue(multipart.contains("name=\"file\"; filename=\"page.png\""));
        assertTrue(multipart.contains("name=\"model\"\r\n\r\nPaddleOCR-VL-1.6"));
        assertTrue(multipart.contains("name=\"optionalPayload\""));
        assertTrue(multipart.contains("useDocOrientationClassify"));
        assertEquals("bucket.bcebos.com",download.uri().getHost());
        assertTrue(download.headers().firstValue("Authorization").isEmpty(),"结果下载不得携带Access Token");

        Path cache=Files.list(temp.resolve("cache/paddle-aistudio")).findFirst().orElseThrow();
        String saved=Files.readString(cache);
        assertTrue(saved.contains("\"pages\""));
        assertTrue(saved.contains("\"layout_id\":\"aistudio-t\""));
        assertFalse(saved.contains("secret-token"));
        assertFalse(saved.contains("signed-secret"));

        ScriptedTransport none=new ScriptedTransport(List.of());
        List<Block> cached=client(properties("different-token",5,5,1),none,(seconds,cancelled)->{})
                .recognize(png(),1000,800,"auto",()->false);
        assertEquals(2,cached.size());
        assertTrue(none.requests.isEmpty(),"相同模型、选项和图片必须命中规范化缓存");
    }

    @Test
    void cancelledRunningJobResumesSameTaskWithoutSubmittingAgain() throws Exception {
        AtomicInteger waits=new AtomicInteger();
        ScriptedTransport initialTransport=new ScriptedTransport(List.of(
                response(200,"{\"code\":0,\"data\":{\"jobId\":\"job-resume\"}}"),
                response(200,"{\"code\":0,\"data\":{\"state\":\"running\"}}")));
        PaddleAiStudioClient initial=client(properties("token-one",5,5,1),initialTransport,(seconds,cancelled)->{
            if(waits.incrementAndGet()>1)throw new CancelledException();
        });
        assertThrows(CancelledException.class,()->initial.recognize(png(),1000,800,"auto",()->false));

        ScriptedTransport resumedTransport=new ScriptedTransport(List.of(
                response(200,"{\"code\":0,\"data\":{\"state\":\"done\",\"resultUrl\":{\"jsonUrl\":\"https://bucket.bcebos.com/result.jsonl\"}}}"),
                response(200,jsonLine())));
        List<Block> blocks=client(properties("token-two",5,5,1),resumedTransport,(seconds,cancelled)->{})
                .recognize(png(),1000,800,"auto",()->false);

        assertEquals(2,blocks.size());
        assertEquals(2,resumedTransport.requests.size());
        assertTrue(resumedTransport.requests.stream().noneMatch(request->"POST".equals(request.method())),
                "已有jobId恢复时不得再次提交计费任务");
        assertEquals("Bearer token-two",resumedTransport.requests.get(0).headers().firstValue("Authorization").orElseThrow());
        assertTrue(resumedTransport.requests.get(1).headers().firstValue("Authorization").isEmpty());
    }

    @Test
    void rejectsMarkdownOnlyMissingCoordinatesAndMaliciousResultUrls() {
        PaddleAiStudioClient client=client(properties("token",5,5,1),new ScriptedTransport(List.of()),(seconds,cancelled)->{});
        assertThrows(OcrException.class,()->client.normalizeJsonLines(
                "{\"result\":{\"dataInfo\":{\"width\":100,\"height\":100},\"layoutParsingResults\":[{\"markdown\":{\"text\":\"# guessed\"},\"prunedResult\":{\"parsing_res_list\":[{\"block_label\":\"text\",\"block_content\":\"guess\"}]}}]}}",100,100));
        assertThrows(OcrException.class,()->PaddleAiStudioClient.validateResultUri("http://bucket.bcebos.com/r.jsonl"));
        assertThrows(OcrException.class,()->PaddleAiStudioClient.validateResultUri("https://127.0.0.1/r.jsonl"));
        assertThrows(OcrException.class,()->PaddleAiStudioClient.validateResultUri("https://evil.example/r.jsonl"));
        assertDoesNotThrow(()->PaddleAiStudioClient.validateResultUri("https://bucket.bcebos.com/r.jsonl?authorization=signed"));
    }

    @Test
    void uncertainSubmitIsCachedAndNeverRetriedOrLeaksToken() throws Exception {
        ScriptedTransport failed=new ScriptedTransport(List.of());
        failed.failure=new IllegalStateException("upstream echoed secret-token and raw body");
        OcrException first=assertThrows(OcrException.class,()->client(properties("secret-token",5,5,1),failed,(s,c)->{})
                .recognize(png(),1000,800,"auto",()->false));
        assertEquals("PaddleOCR AI Studio 提交结果不确定；为避免重复计费未自动重提",first.getMessage());
        assertFalse(first.getMessage().contains("secret-token"));

        ScriptedTransport retry=new ScriptedTransport(List.of());
        OcrException second=assertThrows(OcrException.class,()->client(properties("secret-token",5,5,1),retry,(s,c)->{})
                .recognize(png(),1000,800,"auto",()->false));
        assertTrue(second.getMessage().contains("未自动重提"));
        assertTrue(retry.requests.isEmpty());
        String cache=Files.readString(Files.list(temp.resolve("cache/paddle-aistudio")).findFirst().orElseThrow());
        assertFalse(cache.contains("secret-token"));
    }

    @Test
    void confirmedSubmitRejectionCanBeExplicitlyRetriedAfterConfigurationIsFixed() throws Exception {
        ScriptedTransport rejected=new ScriptedTransport(List.of(response(401,"{\"message\":\"credential rejected\"}")));
        OcrException first=assertThrows(OcrException.class,()->client(properties("expired-token",5,5,1),rejected,(s,c)->{})
                .recognize(png(),1000,800,"auto",()->false));
        assertTrue(first.getMessage().contains("HTTP 401"));
        String rejectedCache=Files.readString(Files.list(temp.resolve("cache/paddle-aistudio")).findFirst().orElseThrow());
        assertTrue(rejectedCache.contains("\"state\":\"rejected\""));
        assertFalse(rejectedCache.contains("expired-token"));

        ScriptedTransport retried=new ScriptedTransport(List.of(
                response(200,"{\"code\":0,\"data\":{\"jobId\":\"job-after-fix\"}}"),
                response(200,"{\"code\":0,\"data\":{\"state\":\"done\",\"resultUrl\":{\"jsonUrl\":\"https://bucket.bcebos.com/result.jsonl\"}}}"),
                response(200,jsonLine())));
        List<Block> blocks=client(properties("fixed-token",5,5,1),retried,(s,c)->{})
                .recognize(png(),1000,800,"auto",()->false);

        assertEquals(2,blocks.size());
        assertEquals(3,retried.requests.size());
        assertEquals("POST",retried.requests.get(0).method(),"显式重试应重新提交，不在一次调用内自动重试");
        assertEquals("Bearer fixed-token",retried.requests.get(0).headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void totalDeadlineStopsPollingAndKeepsTaskForLaterResume() throws Exception {
        ScriptedTransport transport=new ScriptedTransport(List.of(response(200,"{\"code\":0,\"data\":{\"jobId\":\"job-timeout\"}}")));
        PaddleAiStudioClient client=client(properties("token",5,1,1),transport,(seconds,cancelled)->{
            try{Thread.sleep(1050);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancelledException();}
        });

        OcrException error=assertThrows(OcrException.class,()->client.recognize(png(),1000,800,"auto",()->false));

        assertTrue(error.getMessage().contains("总时限"));
        String cache=Files.readString(Files.list(temp.resolve("cache/paddle-aistudio")).findFirst().orElseThrow());
        assertTrue(cache.contains("job-timeout"));
        assertFalse(cache.contains("token"));
    }

    @Test
    void remoteFailedTaskIsNotResubmittedAndDoesNotExposeRemoteBody() {
        ScriptedTransport transport=new ScriptedTransport(List.of(
                response(200,"{\"code\":0,\"data\":{\"jobId\":\"job-failed\"}}"),
                response(200,"{\"code\":0,\"data\":{\"state\":\"failed\",\"message\":\"remote-private-detail\"}}")));
        OcrException first=assertThrows(OcrException.class,()->client(properties("token",5,5,1),transport,(s,c)->{})
                .recognize(png(),1000,800,"auto",()->false));
        assertTrue(first.getMessage().contains("远端任务失败"));
        assertFalse(first.getMessage().contains("remote-private-detail"));

        ScriptedTransport retry=new ScriptedTransport(List.of());
        OcrException second=assertThrows(OcrException.class,()->client(properties("token",5,5,1),retry,(s,c)->{})
                .recognize(png(),1000,800,"auto",()->false));
        assertTrue(second.getMessage().contains("未自动重提"));
        assertTrue(retry.requests.isEmpty());
    }

    @Test
    void cancellationBeforeSubmitPerformsNoNetworkRequest() {
        ScriptedTransport transport=new ScriptedTransport(List.of());
        assertThrows(CancelledException.class,()->client(properties("token",5,5,1),transport,(s,c)->{})
                .recognize(png(),1000,800,"auto",()->true));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void propertiesDoNotExposeAccessTokenInDiagnostics() {
        PaddleAiStudioProperties properties=properties("highly-sensitive-token",5,0,1);
        assertEquals(180,properties.totalTimeoutSeconds());
        assertFalse(properties.toString().contains("highly-sensitive-token"));
        assertTrue(properties.toString().contains("[REDACTED]"));
    }

    private PaddleAiStudioClient client(PaddleAiStudioProperties properties,ScriptedTransport transport,
                                        PaddleAiStudioClient.Waiter waiter){
        AppProperties app=TestConfigs.config(temp,"","");
        return new PaddleAiStudioClient(properties,app,json,new PaddleOcrParser(),transport,waiter);
    }

    private static PaddleAiStudioProperties properties(String token,int request,int total,int poll){
        return new PaddleAiStudioProperties(token,"https://paddleocr.aistudio-app.com/api/v2/ocr/jobs",
                "PaddleOCR-VL-1.6",request,total,poll);
    }

    private static byte[] png(){return new byte[]{(byte)137,80,78,71,13,10,26,10,1,2,3};}
    private static PaddleAiStudioClient.Response response(int status,String body){return new PaddleAiStudioClient.Response(status,body);}
    private static String jsonLine(){return "{\"result\":{\"dataInfo\":{\"width\":1000,\"height\":800},\"layoutParsingResults\":[{\"prunedResult\":{\"parsing_res_list\":[{\"block_id\":\"v\",\"block_order\":1,\"block_label\":\"vertical_text\",\"block_content\":\"原文\",\"block_bbox\":[800,100,850,500]},{\"block_id\":\"t\",\"block_order\":0,\"block_label\":\"table\",\"block_content\":\"<table><tr><td>命盤</td></tr></table>\",\"block_bbox\":[100,100,400,300]}]},\"markdown\":{\"text\":\"ignored\"},\"outputImages\":{\"unsafe\":\"https://example.invalid/x\"}}]}}";}

    private static byte[] body(HttpRequest request)throws Exception{
        HttpRequest.BodyPublisher publisher=request.bodyPublisher().orElseThrow();
        CompletableFuture<byte[]> result=new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>(){
            private final List<byte[]>parts=new ArrayList<>();private int size;
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer item){byte[]bytes=new byte[item.remaining()];item.get(bytes);parts.add(bytes);size+=bytes.length;}
            public void onError(Throwable throwable){result.completeExceptionally(throwable);}
            public void onComplete(){byte[]all=new byte[size];int offset=0;for(byte[]part:parts){System.arraycopy(part,0,all,offset,part.length);offset+=part.length;}result.complete(all);}
        });
        return result.get();
    }

    private static final class ScriptedTransport implements PaddleAiStudioClient.Transport{
        final Deque<PaddleAiStudioClient.Response>responses;final List<HttpRequest>requests=new ArrayList<>();RuntimeException failure;
        ScriptedTransport(List<PaddleAiStudioClient.Response>responses){this.responses=new ArrayDeque<>(responses);}
        public PaddleAiStudioClient.Response send(HttpRequest request,java.util.function.BooleanSupplier cancelled,int maxBytes){requests.add(request);if(failure!=null)throw failure;if(responses.isEmpty())throw new AssertionError("unexpected request "+request.method());return responses.removeFirst();}
    }
}
