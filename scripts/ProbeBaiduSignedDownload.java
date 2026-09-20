import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.regex.*;

public final class ProbeBaiduSignedDownload {
    private static final Pattern ERROR_CODE=Pattern.compile("(?:<Code>|\\\"(?:code|error_code)\\\"\\s*:\\s*\\\"?)([A-Za-z0-9._-]{1,80})(?:</Code>|\\\"?)",Pattern.CASE_INSENSITIVE);
    public static void main(String[]args)throws Exception{
        String raw=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();
        URI uri=validate(raw);
        List<Proxy>proxies=ProxySelector.getDefault()==null?List.of(Proxy.NO_PROXY):ProxySelector.getDefault().select(uri);
        System.out.println("{\"proxyTypes\":"+proxies.stream().map(p->"\\\""+p.type()+"\\\"").distinct().toList()+",\"explicitDefaultPort\":"+(uri.getPort()==443)+"}");
        probe(uri,false,false,"default_headers");
        probe(uri,true,false,"node_headers");
        probe(uri,false,true,"direct_default_headers");
        probe(withoutDefaultPort(uri),false,false,"normalized_default_port");
    }
    private static void probe(URI uri,boolean nodeHeaders,boolean direct,String variant){
        int status=-1;String code=null,negotiated=null,contentType=null,server=null,category=null;
        try{
            HttpClient.Builder clientBuilder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1);
            if(direct)clientBuilder.proxy(new ProxySelector(){public List<Proxy>select(URI ignored){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI ignored,SocketAddress address,IOException error){}});
            HttpClient client=clientBuilder.build();
            HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
            if(nodeHeaders)builder.header("User-Agent","node").header("Accept","*/*").header("Accept-Language","*").header("Accept-Encoding","gzip, deflate").header("Sec-Fetch-Mode","cors");
            HttpRequest request=builder.GET().build();
            HttpResponse<InputStream>response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());status=response.statusCode();negotiated=response.version().toString();
            contentType=safeHeader(response.headers().firstValue("Content-Type").orElse(null));server=safeHeader(response.headers().firstValue("Server").orElse(null));
            try(InputStream in=response.body()){byte[]prefix=in.readNBytes(64*1024);if(status<200||status>=300){String body=new String(prefix,StandardCharsets.UTF_8);Matcher matcher=ERROR_CODE.matcher(body);if(matcher.find())code=matcher.group(1);category=category(body);}}
        }catch(HttpTimeoutException e){code="timeout";}catch(Exception e){code="network_failure";}
        System.out.println("{\"variant\":\""+variant+"\",\"negotiated\":"+quoted(negotiated)+",\"status\":"+status+",\"contentType\":"+quoted(contentType)+",\"server\":"+quoted(server)+",\"errorCode\":"+quoted(code)+",\"errorCategory\":"+quoted(category)+"}");
    }
    private static URI withoutDefaultPort(URI uri){if(uri.getPort()!=443)return uri;String value="https://"+uri.getHost()+(uri.getRawPath()==null?"":uri.getRawPath())+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());return URI.create(value);}
    private static String category(String value){String text=value.toLowerCase(Locale.ROOT);if(text.contains("signature"))return"signature";if(text.contains("expired")||text.contains("expire"))return"expired";if(text.contains("permission")||text.contains("forbidden"))return"permission";if(text.contains("authorization")||text.contains("credential")||text.contains("access"))return"authorization";return"unknown";}
    private static String safeHeader(String value){return value==null?null:value.replaceAll("[^A-Za-z0-9 ._/-]","?").substring(0,Math.min(80,value.length()));}
    private static String quoted(String value){return value==null?"null":"\""+value+"\"";}
    private static URI validate(String raw){
        if(raw==null||raw.isBlank())throw new IllegalArgumentException("stdin缺少结果URL");URI uri=URI.create(raw.strip());String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||!(host.endsWith(".bcebos.com")||host.endsWith(".baidubce.com")))throw new IllegalArgumentException("结果URL未通过安全校验");
        return uri;
    }
}
