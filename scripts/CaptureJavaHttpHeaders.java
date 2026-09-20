import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

public final class CaptureJavaHttpHeaders {
    public static void main(String[]args)throws Exception{
        ArrayBlockingQueue<Map<String,List<String>>>captured=new ArrayBlockingQueue<>(2);
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/object",exchange->{captured.add(exchange.getRequestHeaders());byte[]body="{}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{
            URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/object?authorization=fake");
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build().send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.discarding());
            print("HttpClient",captured.take());
            HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();connection.setInstanceFollowRedirects(false);connection.setRequestMethod("GET");try(InputStream ignored=connection.getInputStream()){}
            print("HttpURLConnection",captured.take());
        }finally{server.stop(0);}
    }
    private static void print(String client,Map<String,List<String>>headers){Map<String,Object>safe=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);headers.forEach((name,values)->safe.put(name,"Host".equalsIgnoreCase(name)?"[HOST]":values));System.out.println(client+" "+safe);}
}
