package studio.bookhtml.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class WriteOriginFilterTest {
 @Test void sameHostOtherPortIsNotSameOrigin() {
  var request=new MockHttpServletRequest();request.setScheme("http");request.setServerName("localhost");request.setServerPort(18765);
  assertTrue(WriteOriginFilter.sameOrigin("http://localhost:18765",request));
  assertFalse(WriteOriginFilter.sameOrigin("http://localhost:8080",request));
  assertFalse(WriteOriginFilter.sameOrigin("https://localhost:18765",request));
  assertFalse(WriteOriginFilter.sameOrigin("null",request));
  assertFalse(WriteOriginFilter.sameOrigin("http://user:pass@localhost:18765",request));
 }
 @Test void crossSiteWriteIsRejectedEvenWithoutOrigin() throws Exception {
  var request=new MockHttpServletRequest("POST","/api/books");request.addHeader("Sec-Fetch-Site","cross-site");
  var response=new MockHttpServletResponse();var chain=new MockFilterChain();
  new WriteOriginFilter().doFilter(request,response,chain);
  assertEquals(403,response.getStatus());assertNull(chain.getRequest());
 }
}
