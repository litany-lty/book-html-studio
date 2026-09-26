package studio.bookhtml.config;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import studio.bookhtml.api.LanPairingController;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SharedLanDefaultsTest {
    LanPairingService access;WriteOriginFilter filter;
    @BeforeEach void setup(){
        access=new LanPairingService();filter=new WriteOriginFilter();filter.setLanPairingService(access);
        ReflectionTestUtils.setField(filter,"bindAddress","0.0.0.0");
    }
    MockHttpServletRequest request(String peer,String method,String path){
        var r=new MockHttpServletRequest(method,path);r.setRemoteAddr(peer);r.setLocalAddr("192.168.1.10");
        r.setServerName("192.168.1.10");r.setServerPort(18765);r.addHeader("Origin","http://192.168.1.10:18765");return r;
    }
    boolean allowed(MockHttpServletRequest r)throws Exception{
        var ok=new AtomicBoolean();filter.doFilter(r,new MockHttpServletResponse(),(a,b)->ok.set(true));return ok.get();
    }
    @Test void directPrivatePeersUploadWithoutAccountPinOrCookie()throws Exception{
        for(String peer:List.of("10.1.2.3","172.16.1.5","172.31.255.1","192.168.1.50","169.254.5.6","fd12::42","fe80::42","::ffff:192.168.1.50"))
            assertTrue(allowed(request(peer,"POST","/api/books")),peer);
        assertEquals(0,access.activeTokenCount(),"direct upload must not mint a credential");
    }
    @Test void statusDescribesSharedReadUploadButNotEditPaidOrManage(){
        var status=new LanPairingController(access).status(request("192.168.1.50","GET","/api/lan/status")).getBody();
        assertEquals(Set.of(LanPairingService.LanCapability.READ,LanPairingService.LanCapability.UPLOAD),status.get("capabilities"));
        assertEquals("SHARED",status.get("libraryScope"));assertEquals(Boolean.FALSE,status.get("isPaired"));
    }
    @Test void browserOriginProofRemainsMandatoryForUnpairedUpload()throws Exception{
        var r=request("192.168.1.50","POST","/api/books");r.removeHeader("Origin");assertFalse(allowed(r));
        r=request("192.168.1.50","POST","/api/books");r.removeHeader("Origin");r.addHeader("Referer","http://192.168.1.10:18765/");assertTrue(allowed(r));
    }
    @Test void publicMalformedAndForwardedPeersDoNotGainImplicitUpload()throws Exception{
        for(String peer:List.of("8.8.8.8","172.15.1.2","172.32.1.2","100.64.1.2","192.0.2.50","2001:4860::1","255.255.255.255","localhost","10.1","010.1.2.3","10.1.2.3.evil.invalid","fd00::1%evil/host","fe00::1"))
            assertFalse(allowed(request(peer,"POST","/api/books")),peer);
        for(String header:List.of("Forwarded","X-Forwarded-For","X-Real-IP")){
            var r=request("192.168.1.50","POST","/api/books");r.addHeader(header,"192.168.1.1");assertFalse(allowed(r),header);
        }
    }
    @Test void directUploadCannotChangeCommonTextOrStartPaidWork()throws Exception{
        for(String path:List.of("/api/books/id/jobs","/api/books/id/reading-window","/api/books/id/pages/1/issues/i/decision-jobs","/api/books/id/pages/1","/api/books/id/library","/api/cloud-consents","/api/reading-policy","/api/settings","/api/lan/pin","/api/books/"))
            assertFalse(allowed(request("192.168.1.50","POST",path)),path);
    }
    @Test void explicitlyRestrictedExistingDeploymentIsNotSilentlyRelaxed()throws Exception{
        access.setLanReadRequiresPairing(true);
        assertFalse(allowed(request("192.168.1.50","POST","/api/books")));
        assertFalse(allowed(request("192.168.1.50","GET","/api/books")));
        access.setPin("123456");var token=access.pair("123456","192.168.1.50").token();
        var r=request("192.168.1.50","POST","/api/books");r.addHeader("X-Lan-Pairing-Token",token);assertTrue(allowed(r));
    }
    @Test void crossOriginAndSpoofedHostStillCannotUpload()throws Exception{
        for(String origin:List.of("null","https://evil.invalid","http://192.168.1.10:9999")){
            var r=request("192.168.1.50","POST","/api/books");r.removeHeader("Origin");r.addHeader("Origin",origin);assertFalse(allowed(r));
        }
        var r=request("192.168.1.50","POST","/api/books");r.setServerName("evil.invalid");r.removeHeader("Origin");r.addHeader("Origin","http://evil.invalid:18765");assertFalse(allowed(r));
    }
    @Test void freshServerStillAllowsSameLanUploadWithoutRestoringTokens()throws Exception{
        assertTrue(allowed(request("192.168.1.50","POST","/api/books")));
        access=new LanPairingService();filter.setLanPairingService(access);
        assertTrue(allowed(request("192.168.1.50","POST","/api/books")));assertEquals(0,access.activeTokenCount());
    }
}
