package studio.bookhtml.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import studio.bookhtml.api.LanPairingController;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G13 / B11: 局域网访问授权与能力分级认证专项测试。
 * 验证：
 *  - 本机回环模式全能力放行
 *  - 局域网未配对写操作 401 阻断
 *  - 配对码暴力破解锁定
 *  - 能力分级（READ / EDIT / PAID / MANAGE）权限阶梯
 *  - 凭据撤销与过期
 *  - CSP 与安全响应头合规性
 */
class LanCapabilityTest {

    private LanPairingService pairingService;
    private WriteOriginFilter filter;
    private LanPairingController controller;

    @BeforeEach
    void setUp() {
        pairingService = new LanPairingService();
        filter = new WriteOriginFilter();
        filter.setLanPairingService(pairingService);
        ReflectionTestUtils.setField(filter, "bindAddress", "0.0.0.0");
        controller = new LanPairingController(pairingService);
    }

    private MockHttpServletRequest createRequest(String method, String uri, String remoteIp) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setServerName("192.168.1.100");
        req.setLocalAddr("192.168.1.100");
        req.setServerPort(18765);
        req.setRemoteAddr(remoteIp);
        req.addHeader("Origin", "http://192.168.1.100:18765");
        return req;
    }

    private boolean doFilter(MockHttpServletRequest req, MockHttpServletResponse res) throws Exception {
        AtomicBoolean passed = new AtomicBoolean(false);
        filter.doFilter(req, res, (q, r) -> passed.set(true));
        return passed.get();
    }

    @Test
    void loopbackRequestsHaveFullCapabilitiesWithoutPairing() throws Exception {
        MockHttpServletRequest req = createRequest("POST", "/api/books/test-id/pages/1", "127.0.0.1");
        req.setServerName("127.0.0.1");
        req.setLocalAddr("127.0.0.1");
        req.removeHeader("Origin");
        req.addHeader("Origin", "http://127.0.0.1:18765");

        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(doFilter(req, res), "本机回环请求应无阻碍直接放行");
        assertEquals(200, res.getStatus());
        assertTrue(pairingService.isAllowed(req, LanPairingService.LanCapability.MANAGE));
    }

    @Test
    void lanUnpairedWriteRequestIsRejectedWith401() throws Exception {
        MockHttpServletRequest req = createRequest("POST", "/api/books/test-id/pages/1", "192.168.1.50");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = doFilter(req, res);
        assertFalse(allowed, "局域网未配对的写操作必须被阻断");
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("局域网写操作需先通过配对码授权"));
    }

    @Test
    void bruteForcePairingAttemptsTrigger60SecondLockout() {
        pairingService.setPin("888888");

        // 连续 5 次错误尝试
        for (int i = 0; i < 5; i++) {
            var result = pairingService.pair("000000", "192.168.1.50");
            assertFalse(result.success());
        }

        assertTrue(pairingService.isLocked(), "5 次错误配对尝试后必须锁定");

        // 锁定期间即使输入正确 PIN 也被阻断
        var lockedResult = pairingService.pair("888888", "192.168.1.50");
        assertFalse(lockedResult.success());
        assertTrue(lockedResult.message().contains("锁定"));

        // 重置锁定
        pairingService.resetLock();
        assertFalse(pairingService.isLocked());
        var successResult = pairingService.pair("888888", "192.168.1.50");
        assertTrue(successResult.success());
    }

    @Test
    void pairingTokensEnforceCapabilityLadderCorrectly() throws Exception {
        // 1. 注册仅具 READ 能力的令牌
        String readOnlyToken = "token-read-only";
        pairingService.registerToken(readOnlyToken, Set.of(LanPairingService.LanCapability.READ), Duration.ofHours(1));

        MockHttpServletRequest editReq = createRequest("POST", "/api/books/test-id/pages/1", "192.168.1.50");
        editReq.addHeader("X-Lan-Pairing-Token", readOnlyToken);
        MockHttpServletResponse editRes = new MockHttpServletResponse();

        assertFalse(doFilter(editReq, editRes), "READ 凭证不得执行页面编辑写操作");
        assertEquals(403, editRes.getStatus());
        assertTrue(editRes.getContentAsString().contains("缺乏所需权限"));

        // 2. 注册具 EDIT 能力的令牌
        String editToken = "token-edit-granted";
        pairingService.registerToken(editToken, Set.of(LanPairingService.LanCapability.READ, LanPairingService.LanCapability.EDIT), Duration.ofHours(1));

        MockHttpServletRequest editAllowedReq = createRequest("POST", "/api/books/test-id/pages/1", "192.168.1.50");
        editAllowedReq.addHeader("X-Lan-Pairing-Token", editToken);
        MockHttpServletResponse editAllowedRes = new MockHttpServletResponse();

        assertTrue(doFilter(editAllowedReq, editAllowedRes), "持有 EDIT 能力的凭证允许页面编辑");

        // 3. EDIT 凭证尝试触发 PAID（云任务）
        MockHttpServletRequest paidReq = createRequest("POST", "/api/jobs/batch-ocr", "192.168.1.50");
        paidReq.addHeader("X-Lan-Pairing-Token", editToken);
        MockHttpServletResponse paidRes = new MockHttpServletResponse();

        assertFalse(doFilter(paidReq, paidRes), "EDIT 凭证严禁未经 PAID 授权触发云端 OCR 计费");
        assertEquals(403, paidRes.getStatus());

        // 4. 注册具 PAID 能力的令牌
        String paidToken = "token-paid-granted";
        pairingService.registerToken(paidToken, Set.of(LanPairingService.LanCapability.READ, LanPairingService.LanCapability.PAID), Duration.ofHours(1));

        MockHttpServletRequest paidAllowedReq = createRequest("POST", "/api/jobs/batch-ocr", "192.168.1.50");
        paidAllowedReq.addHeader("X-Lan-Pairing-Token", paidToken);
        MockHttpServletResponse paidAllowedRes = new MockHttpServletResponse();

        assertTrue(doFilter(paidAllowedReq, paidAllowedRes), "持有 PAID 权限允许提交云端计费作业");

        // 5. PAID 凭证尝试执行 MANAGE（删除书籍或修改设置）
        MockHttpServletRequest manageReq = createRequest("DELETE", "/api/books/test-id", "192.168.1.50");
        manageReq.addHeader("X-Lan-Pairing-Token", paidToken);
        MockHttpServletResponse manageRes = new MockHttpServletResponse();

        assertFalse(doFilter(manageReq, manageRes), "PAID 凭证严禁执行删除书籍等 MANAGE 管理操作");
        assertEquals(403, manageRes.getStatus());

        // 6. 注册具 MANAGE 能力的令牌
        String manageToken = "token-manage-granted";
        pairingService.registerToken(manageToken, Set.of(LanPairingService.LanCapability.MANAGE), Duration.ofHours(1));

        MockHttpServletRequest manageAllowedReq = createRequest("DELETE", "/api/books/test-id", "192.168.1.50");
        manageAllowedReq.addHeader("X-Lan-Pairing-Token", manageToken);
        MockHttpServletResponse manageAllowedRes = new MockHttpServletResponse();

        assertTrue(doFilter(manageAllowedReq, manageAllowedRes), "持有 MANAGE 权限允许执行系统级管理");
    }

    @Test
    void revokedAndExpiredTokensAreImmediatelyRejected() throws Exception {
        String token = "temp-token-to-expire";
        pairingService.registerToken(token, Set.of(LanPairingService.LanCapability.EDIT), Duration.ofMillis(10));
        Thread.sleep(25); // 等待过期

        MockHttpServletRequest req = createRequest("POST", "/api/books/test-id/pages/1", "192.168.1.50");
        req.addHeader("X-Lan-Pairing-Token", token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertFalse(doFilter(req, res), "过期凭据必须被阻断");
        assertEquals(401, res.getStatus());

        // 撤销测试
        String validToken = "token-to-revoke";
        pairingService.registerToken(validToken, Set.of(LanPairingService.LanCapability.EDIT), Duration.ofHours(1));
        assertTrue(pairingService.isValidToken(validToken));
        pairingService.revokeToken(validToken);
        assertFalse(pairingService.isValidToken(validToken));
    }

    @Test
    void strictSecurityHeadersAndCspAreEnforcedOnAllResponses() throws Exception {
        MockHttpServletRequest req = createRequest("GET", "/api/lan/status", "192.168.1.50");
        MockHttpServletResponse res = new MockHttpServletResponse();
        doFilter(req, res);

        assertEquals("nosniff", res.getHeader("X-Content-Type-Options"));
        assertEquals("same-origin", res.getHeader("Referrer-Policy"));
        assertEquals("DENY", res.getHeader("X-Frame-Options"));

        String csp = res.getHeader("Content-Security-Policy");
        assertNotNull(csp, "响应中必须包含 Content-Security-Policy 头");
        assertTrue(csp.contains("default-src 'self'"));
        assertTrue(csp.contains("frame-ancestors 'none'"));
        assertTrue(csp.contains("base-uri 'self'"));
    }

    @Test
    void crossOriginRefererIsStrictlyRejectedWhenOriginAbsent() throws Exception {
        MockHttpServletRequest req = createRequest("POST", "/api/books/test-id/pages/1", "127.0.0.1");
        req.setServerName("127.0.0.1");
        req.setLocalAddr("127.0.0.1");
        req.removeHeader("Origin"); // 无 Origin，但包含恶意 Referer
        req.addHeader("Referer", "https://evil-attacker.example/attack.html");

        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(doFilter(req, res), "非同源恶意 Referer 必须被拒绝");
        assertEquals(403, res.getStatus());
    }

    @Test
    void lanPairingControllerFlow() {
        pairingService.setPin("654321");

        // 1. 配对
        var pairResp = controller.pair(createRequest("POST", "/api/lan/pair", "192.168.1.50"),
                new LanPairingController.PairRequest("654321"));
        assertEquals(200, pairResp.getStatusCode().value());
        assertTrue((Boolean) pairResp.getBody().get("success"));
        String token = (String) pairResp.getBody().get("token");
        assertNotNull(token);

        // 2. 状态查询
        MockHttpServletRequest statusReq = createRequest("GET", "/api/lan/status", "192.168.1.50");
        statusReq.addHeader("X-Lan-Pairing-Token", token);
        var statusResp = controller.status(statusReq);
        assertEquals(200, statusResp.getStatusCode().value());
        assertTrue((Boolean) statusResp.getBody().get("isPaired"));
        assertEquals("LAN_PAIRED", statusResp.getBody().get("accessMode"));
    }
}
