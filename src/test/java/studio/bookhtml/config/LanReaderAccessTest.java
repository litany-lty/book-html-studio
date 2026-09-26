package studio.bookhtml.config;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import studio.bookhtml.api.LanPairingController;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LanReaderAccessTest {
    LanPairingService pairing;WriteOriginFilter filter;
    @BeforeEach void setup(){
        pairing=new LanPairingService();filter=new WriteOriginFilter();filter.setLanPairingService(pairing);
        ReflectionTestUtils.setField(filter,"bindAddress","0.0.0.0");
        pairing.registerToken("fixture-editor",Set.of(LanPairingService.LanCapability.EDIT),Duration.ofHours(1));
    }
    MockHttpServletRequest request(String method,String path){
        var r=new MockHttpServletRequest(method,path);r.setServerName("192.168.1.100");r.setLocalAddr("192.168.1.100");
        r.setServerPort(18765);r.setRemoteAddr("192.168.1.50");r.addHeader("Origin","http://192.168.1.100:18765");return r;
    }
    boolean passes(MockHttpServletRequest request,MockHttpServletResponse response)throws Exception{
        var reached=new AtomicBoolean();filter.doFilter(request,response,(a,b)->reached.set(true));return reached.get();
    }
    @Test void existingEditGrantCannotStartActualBookOcrOrDecisionRoutes()throws Exception{
        for(String path:List.of("/api/books/book/jobs","/api/books/book/reading-window","/api/books/book/pages/1/issues/i/decision-jobs")){
            var r=request("POST",path);r.addHeader("X-Lan-Pairing-Token","fixture-editor");var response=new MockHttpServletResponse();
            assertFalse(passes(r,response),path);assertEquals(403,response.getStatus());
        }
    }
    @Test void editGrantCannotMintCloudAuthorizationOrChangeAutomaticPolicy()throws Exception{
        for(String path:List.of("/api/cloud-consents","/api/cloud-consents/id/revoke","/api/reading-policy")){
            var r=request(path.endsWith("policy")?"PUT":"POST",path);r.addHeader("X-Lan-Pairing-Token","fixture-editor");
            assertFalse(passes(r,new MockHttpServletResponse()),path);
        }
    }
    @Test void browserPairingIssuesHttpOnlyCookieWithoutReturningTokenBody()throws Exception{
        pairing.setPin("654321");var mvc=MockMvcBuilders.standaloneSetup(new LanPairingController(pairing)).build();
        var result=mvc.perform(post("/api/lan/browser-pair").contentType("application/json").content("{\"pin\":\"654321\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.token").doesNotExist()).andReturn();
        String cookie=result.getResponse().getHeader("Set-Cookie");assertNotNull(cookie);assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));
    }

    @Test void cookieCanUploadButCannotEditPayOrManageSharedContent()throws Exception{
        pairing.setPin("654321");var controller=new LanPairingController(pairing);
        var paired=controller.browserPair(request("POST","/api/lan/browser-pair"),new LanPairingController.PairRequest("654321"));
        String raw=paired.getHeaders().getFirst("Set-Cookie").split(";",2)[0].split("=",2)[1];
        for(String path:List.of("/api/books","/api/books/id/pages/1","/api/books/id/jobs","/api/reading-policy","/api/books/id/library","/api/lan/pin")){
            var r=request("POST",path);r.setCookies(new jakarta.servlet.http.Cookie(LanPairingService.BROWSER_COOKIE,raw));
            assertEquals(path.equals("/api/books"),passes(r,new MockHttpServletResponse()),path);
        }
        assertEquals(Set.of(LanPairingService.LanCapability.READ,LanPairingService.LanCapability.UPLOAD),pairing.getCapabilities(raw));
    }
    @Test void queryStringCredentialIsNotAcceptedAndCookieRequiresOrigin()throws Exception{
        var r=request("POST","/api/books/id/pages/1");r.addParameter("lanToken","fixture-editor");
        assertFalse(passes(r,new MockHttpServletResponse()));
        r=request("POST","/api/books/id/pages/1");r.setCookies(new jakarta.servlet.http.Cookie(LanPairingService.BROWSER_COOKIE,"fixture-editor"));r.removeHeader("Origin");
        assertFalse(passes(r,new MockHttpServletResponse()));
    }
    @Test void unrelatedDevicesReadTheSameShelfWithoutSharingWritePermission()throws Exception{
        assertTrue(passes(request("GET","/api/books?view=reader"),new MockHttpServletResponse()));
        assertTrue(passes(request("GET","/api/books/id/reader/pages/1"),new MockHttpServletResponse()));
        assertTrue(passes(request("POST","/api/books"),new MockHttpServletResponse()),"default shared LAN upload is not an edit grant");
        pairing.setLanReadRequiresPairing(true);
        assertFalse(passes(request("POST","/api/books"),new MockHttpServletResponse()));
        assertFalse(passes(request("GET","/api/books"),new MockHttpServletResponse()));
    }
    @Test void browserPairRejectsMissingOrCrossOriginBeforeIssuingCredential()throws Exception{
        for(String origin:List.of("", "http://other.invalid", "null")){
            var r=request("POST","/api/lan/browser-pair");r.removeHeader("Origin");if(!origin.isEmpty())r.addHeader("Origin",origin);
            assertFalse(passes(r,new MockHttpServletResponse()));assertEquals(1,pairing.activeTokenCount(),"only the pre-existing fixture token remains");
        }
    }
    @Test void logoutRevokesOnlyCurrentDeviceAndClearsCookie()throws Exception{
        pairing.setPin("123456");var controller=new LanPairingController(pairing);
        var first=controller.browserPair(request("POST","/api/lan/browser-pair"),new LanPairingController.PairRequest("123456"));
        String token=first.getHeaders().getFirst("Set-Cookie").split(";",2)[0].split("=",2)[1];
        var r=request("POST","/api/lan/browser-logout");r.setCookies(new jakarta.servlet.http.Cookie(LanPairingService.BROWSER_COOKIE,token));
        assertEquals(0,controller.browserLogout(r).getHeaders().getFirst("Set-Cookie").indexOf(LanPairingService.BROWSER_COOKIE+"=;"));
        assertFalse(pairing.isValidToken(token));assertTrue(pairing.isValidToken("fixture-editor"));
    }
    @Test void duplicateCookiesAreRejectedRatherThanChoosingAUser()throws Exception{
        var r=request("POST","/api/books/id/pages/1");r.setCookies(new jakarta.servlet.http.Cookie(LanPairingService.BROWSER_COOKIE,"fixture-editor"),new jakarta.servlet.http.Cookie(LanPairingService.BROWSER_COOKIE,"another"));
        assertNull(LanPairingService.extractToken(r));assertFalse(passes(r,new MockHttpServletResponse()));
    }
    @Test void httpsBrowserPairUsesSecureCookieAndStatusNeverExposesToken()throws Exception{
        pairing.setPin("123456");var request=request("POST","/api/lan/browser-pair");request.setSecure(true);
        var controller=new LanPairingController(pairing);var result=controller.browserPair(request,new LanPairingController.PairRequest("123456"));
        assertTrue(result.getHeaders().getFirst("Set-Cookie").contains("Secure"));assertFalse(result.getBody().containsKey("token"));
        assertEquals("SHARED",controller.status(request).getBody().get("libraryScope"));
    }

    @Test void legacyPairEndpointCannotUpgradeTheSameUploadPinToEdit()throws Exception{
        pairing.setPin("123456");var granted=pairing.pair("123456","192.0.2.50");
        assertTrue(granted.success());assertTrue(granted.capabilities().contains(LanPairingService.LanCapability.UPLOAD));
        assertFalse(granted.capabilities().contains(LanPairingService.LanCapability.EDIT));
        assertFalse(granted.capabilities().contains(LanPairingService.LanCapability.PAID));
    }
    @Test void unknownAndEncodedMutatingPathsDoNotInheritEditAuthority()throws Exception{
        for(String path:List.of("/api/books/id/%6aobs","/api/books/id/jobs;path=x","/api/something-new","/api/books/id//jobs")){
            var r=request("POST",path);r.addHeader("X-Lan-Pairing-Token","fixture-editor");
            assertFalse(passes(r,new MockHttpServletResponse()),path);
        }
    }
}
