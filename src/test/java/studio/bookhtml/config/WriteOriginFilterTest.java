package studio.bookhtml.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
class WriteOriginFilterTest {
    private boolean allowed(String method,String host,int port,String origin,String site) throws Exception {
        var req=new MockHttpServletRequest(method,"/api/settings");
        req.setScheme("http");req.setServerName(host);req.setServerPort(port);req.setLocalAddr("127.0.0.1");
        if(origin!=null)req.addHeader("Origin",origin);if(site!=null)req.addHeader("Sec-Fetch-Site",site);
        var res=new MockHttpServletResponse();var passed=new AtomicBoolean();
        new WriteOriginFilter().doFilter(req,res,(a,b)->passed.set(true));
        assertEquals("no-store",res.getHeader("Cache-Control"));
        assertEquals("DENY",res.getHeader("X-Frame-Options"));
        if(!passed.get())assertEquals(403,res.getStatus());
        return passed.get();
    }
    @Test void exactOriginAndLocalCliAllowed() throws Exception {
        assertTrue(allowed("PUT","localhost",8080,"http://localhost:8080",null));
        assertTrue(allowed("PUT","127.0.0.1",8080,null,null));
    }
    @Test void crossPortAndMalformedOriginsRejected() throws Exception {
        for(String origin:new String[]{"http://localhost:9000","https://localhost:8080","null","http://localhost:8080/","http://user@localhost:8080","http://localhost:8080#x"})
            assertFalse(allowed("PUT","localhost",8080,origin,null),origin);
    }
    @Test void rebindingAndCrossSiteWritesRejected() throws Exception {
        assertFalse(allowed("GET","attacker.invalid",8080,null,null));
        assertFalse(allowed("POST","localhost",8080,null,"cross-site"));
    }
}
