package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;

import java.net.http.HttpRequest;
import java.net.*;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;

class PaddleOcrClientTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    @Test void submitsPollsDownloadsWithoutLeakingTokenAndCachesResult()throws Exception{
        ScriptedTransport transport=new ScriptedTransport(List.of(
                response(200,"{\"access_token\":\"secret-token\",\"expires_in\":3600}"),
                response(200,"{\"error_code\":0,\"result\":{\"task_id\":\"task-1\"}}"),
                response(200,"{\"error_code\":0,\"result\":{\"status\":\"success\",\"parse_result_url\":\"https://bucket.bcebos.com/result.json?signature=secret\"}}"),
                response(200,resultJson())));
        PaddleOcrClient client=new PaddleOcrClient(config(temp),json,new PaddleOcrParser(),transport,(s,c)->{});
        List<Block>blocks=client.recognize(png(),1000,800,"auto",()->false);
        assertEquals(2,blocks.size());assertEquals("table",blocks.get(1).type());assertEquals(4,transport.requests.size());
        HttpRequest download=transport.requests.get(3);assertEquals("bucket.bcebos.com",download.uri().getHost());assertEquals("signature=secret",download.uri().getQuery());assertFalse(download.uri().getQuery().contains("access_token"));assertTrue(download.headers().firstValue("Authorization").isEmpty());
        String cache=Files.readString(Files.list(temp.resolve("cache/paddle")).findFirst().orElseThrow());assertFalse(cache.contains("secret-token"));assertFalse(cache.contains("signature=secret"));assertTrue(cache.contains("task-1"));
        ScriptedTransport none=new ScriptedTransport(List.of());List<Block>cached=new PaddleOcrClient(config(temp),json,new PaddleOcrParser(),none,(s,c)->{}).recognize(png(),1000,800,"auto",()->false);assertEquals(2,cached.size());assertTrue(none.requests.isEmpty());
    }
    @Test void cancelledRunningTaskResumesSameTaskWithoutSecondSubmit()throws Exception{
        AtomicInteger waits=new AtomicInteger();ScriptedTransport first=new ScriptedTransport(List.of(response(200,"{\"access_token\":\"t1\",\"expires_in\":3600}"),response(200,"{\"error_code\":0,\"result\":{\"task_id\":\"task-resume\"}}"),response(200,"{\"error_code\":0,\"result\":{\"status\":\"running\"}}")));
        PaddleOcrClient initial=new PaddleOcrClient(config(temp),json,new PaddleOcrParser(),first,(s,c)->{if(waits.incrementAndGet()>1)throw new CancelledException();});assertThrows(CancelledException.class,()->initial.recognize(png(),1000,800,"auto",()->false));
        ScriptedTransport resumed=new ScriptedTransport(List.of(response(200,"{\"access_token\":\"t2\",\"expires_in\":3600}"),response(200,"{\"error_code\":0,\"result\":{\"status\":\"done\",\"parse_result_url\":\"https://bucket.bcebos.com/r.json\"}}"),response(200,resultJson())));
        List<Block>blocks=new PaddleOcrClient(config(temp),json,new PaddleOcrParser(),resumed,(s,c)->{}).recognize(png(),1000,800,"auto",()->false);assertEquals(2,blocks.size());assertEquals(3,resumed.requests.size());assertTrue(resumed.requests.stream().noneMatch(r->r.uri().getPath().endsWith("/task")));
        assertTrue(resumed.requests.get(1).bodyPublisher().isPresent());
    }
    @Test void rejectsMaliciousResultUrls(){assertThrows(OcrException.class,()->PaddleOcrClient.validateResultUri("http://bucket.bcebos.com/x"));assertThrows(OcrException.class,()->PaddleOcrClient.validateResultUri("https://127.0.0.1/x"));assertThrows(OcrException.class,()->PaddleOcrClient.validateResultUri("https://evil.example/x"));assertDoesNotThrow(()->PaddleOcrClient.validateResultUri("https://bucket.bcebos.com/x?a=1"));}
    @Test void signedDownloadDoesNotAddEntityHeaderThatBreaksBosSignature()throws Exception{AtomicReference<String>contentLength=new AtomicReference<>();HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.createContext("/result",exchange->{contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));byte[]body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();try{URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/result?authorization=fake");PaddleOcrClient.Response response=PaddleOcrClient.signedGet(uri,Duration.ofSeconds(2),()->false,1024,Proxy.NO_PROXY);assertEquals(200,response.status());assertNull(contentLength.get());}finally{server.stop(0);}}
    private AppProperties config(Path data){return new AppProperties(data,300,5000,2400,"tesseract","","qwen3.5-ocr","https://dashscope.aliyuncs.com/api/v1",5,"","MiniMax-M3","https://api.minimax.cn/v1",5,false,"api-key","secret-key","PaddleOCR-VL-1.6","https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task",5,5,1);}
    private static byte[]png(){return new byte[]{(byte)137,80,78,71,13,10,26,10,1,2,3};}
    private static String resultJson(){return "{\"pages\":[{\"layouts\":[{\"layout_id\":\"a\",\"type\":\"vertical_text\",\"text\":\"原文\",\"position\":[800,100,50,400]},{\"layout_id\":\"b\",\"type\":\"table\",\"text\":\"<table><tr><td>命盤</td></tr></table>\",\"position\":[100,100,300,300]}]}]}";}
    private static PaddleOcrClient.Response response(int status,String body){return new PaddleOcrClient.Response(status,body);}
    private static final class ScriptedTransport implements PaddleOcrClient.Transport{final Deque<PaddleOcrClient.Response>responses;final List<HttpRequest>requests=new ArrayList<>();ScriptedTransport(List<PaddleOcrClient.Response>responses){this.responses=new ArrayDeque<>(responses);}public PaddleOcrClient.Response send(HttpRequest request,java.util.function.BooleanSupplier cancelled,int maxBytes){requests.add(request);if(responses.isEmpty())throw new AssertionError("unexpected request "+request.uri().getPath());return responses.removeFirst();}}
}
