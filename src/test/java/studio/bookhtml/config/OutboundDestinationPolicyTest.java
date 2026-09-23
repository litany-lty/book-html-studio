package studio.bookhtml.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studio.bookhtml.service.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G13 / B11: 出站网络目的地址安全策略（SSRF 与外发白名单防护）专项测试。
 * 验证：
 *  - 默认主流 OCR/LLM 云端端点白名单放行
 *  - 云元数据端点（AWS/GCP/Aliyun）绝对阻断
 *  - 危险协议（file, gopher, ftp, javascript）阻断
 *  - 私有网段与本地回环默认阻断
 *  - 本地 OCR 守护进程可按需受控放行
 *  - IP 进制混淆与凭据走私防护
 *  - PhysicalCallService 运行时集成阻断
 */
class OutboundDestinationPolicyTest {

    private OutboundDestinationPolicy defaultPolicy;

    @BeforeEach
    void setUp() {
        defaultPolicy = new OutboundDestinationPolicy();
    }

    @Test
    void defaultWhitelistedCloudApiEndpointsAreAllowed() {
        assertTrue(defaultPolicy.isAllowed("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"));
        assertTrue(defaultPolicy.isAllowed("https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic"));
        assertTrue(defaultPolicy.isAllowed("https://aistudio.baidu.com/api/v1/ocr"));
        assertTrue(defaultPolicy.isAllowed("https://api.minimax.chat/v1/text/chatcompletion_v2"));
    }

    @Test
    void cloudMetadataEndpointsAreStrictlyBlocked() {
        // AWS / Azure 169.254.169.254
        var awsRes = defaultPolicy.validate("http://169.254.169.254/latest/meta-data/");
        assertFalse(awsRes.isAllowed(), "必须阻断 169.254.169.254 元数据端点");
        assertTrue(awsRes.reason().contains("元数据") || awsRes.reason().contains("链路本地"));

        // Aliyun 100.100.100.200
        var aliRes = defaultPolicy.validate("http://100.100.100.200/latest/meta-data/");
        assertFalse(aliRes.isAllowed(), "必须阻断 100.100.100.200 阿里元数据端点");

        // GCP metadata.google.internal
        var gcpRes = defaultPolicy.validate("http://metadata.google.internal/computeMetadata/v1/");
        assertFalse(gcpRes.isAllowed(), "必须阻断 metadata.google.internal 端点");

        // 简写 metadata
        var shortRes = defaultPolicy.validate("http://metadata/computeMetadata/v1/");
        assertFalse(shortRes.isAllowed());
    }

    @Test
    void dangerousSchemesAreStrictlyBlocked() {
        assertFalse(defaultPolicy.isAllowed("file:///etc/passwd"));
        assertFalse(defaultPolicy.isAllowed("gopher://127.0.0.1:6379/_"));
        assertFalse(defaultPolicy.isAllowed("ftp://ftp.example.com/file"));
        assertFalse(defaultPolicy.isAllowed("javascript:alert(1)"));
        assertFalse(defaultPolicy.isAllowed("data:text/html,<html></html>"));
    }

    @Test
    void privateAndLoopbackIpAddressesAreBlockedByDefault() {
        // 10.0.0.0/8
        assertFalse(defaultPolicy.isAllowed("http://10.0.0.1:8080/internal-api"));
        // 172.16.0.0/12
        assertFalse(defaultPolicy.isAllowed("http://172.16.50.2/api"));
        // 192.168.0.0/16
        assertFalse(defaultPolicy.isAllowed("http://192.168.1.1/admin"));
        // Loopback 127.0.0.1
        assertFalse(defaultPolicy.isAllowed("http://127.0.0.1:8000/ocr"));
        // localhost
        assertFalse(defaultPolicy.isAllowed("http://localhost:8000/ocr"));
    }

    @Test
    void localOcrLoopbackCanBePermittedWhenConfigured() {
        OutboundDestinationPolicy localOcrPolicy = new OutboundDestinationPolicy(true, false, List.of());

        // 本地 OCR 守护进程允许
        assertTrue(localOcrPolicy.isAllowed("http://127.0.0.1:8000/ocr"));
        assertTrue(localOcrPolicy.isAllowed("http://localhost:8000/ocr"));

        // 但即使开启回环，云元数据依然绝对禁止
        assertFalse(localOcrPolicy.isAllowed("http://169.254.169.254/latest/meta-data/"));
        // 私网其他机器依然禁止
        assertFalse(localOcrPolicy.isAllowed("http://192.168.1.1/admin"));
    }

    @Test
    void ipObfuscationAttacksAreDetectedAndBlocked() {
        // 十进制整型 IP 绕过 (2130706433 即 127.0.0.1)
        assertFalse(defaultPolicy.isAllowed("http://2130706433/"));
        // 八进制 IP 绕过
        assertFalse(defaultPolicy.isAllowed("http://0177.0.0.1/"));
        // 十六进制 IP 绕过
        assertFalse(defaultPolicy.isAllowed("http://0x7f000001/"));
        // IPv6 映射的 IPv4 元数据地址
        assertFalse(defaultPolicy.isAllowed("http://[::ffff:169.254.169.254]/"));
    }

    @Test
    void userInfoSmugglingAndControlCharactersAreBlocked() {
        // UserInfo 走私
        var res = defaultPolicy.validate("https://admin:supersecret@dashscope.aliyuncs.com/api");
        assertFalse(res.isAllowed(), "携带 UserInfo 凭据的请求必须被阻断");
        assertTrue(res.reason().contains("UserInfo"));

        // 控制字符注入
        assertFalse(defaultPolicy.isAllowed("https://dashscope.aliyuncs.com/api\r\nHost: evil.com"));
        assertFalse(defaultPolicy.isAllowed("https://dashscope.aliyuncs.com/api\0extra"));
    }

    @Test
    void customAllowedHostsCanBeRegisteredDynamically() {
        assertFalse(defaultPolicy.isAllowed("https://ocr.enterprise.internal/v1"));
        defaultPolicy.addAllowedHost("ocr.enterprise.internal");
        assertTrue(defaultPolicy.isAllowed("https://ocr.enterprise.internal/v1"));
    }

    @Test
    void physicalCallServiceAbortsBlockedDestinationBeforeNetworkTransmission() throws Exception {
        var resources = new ProviderResourceRegistry();
        var budgets = new AttemptCallBudgetStore();
        var delayed = new DelayedCallQueue();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();

        PhysicalCallService service = new PhysicalCallService(resources, budgets, delayed, json);
        service.setOutboundPolicy(defaultPolicy);

        // 尝试向内网元数据发起请求
        PhysicalCallCommand command = new PhysicalCallCommand(
                "book-1", 1, PhysicalCallCommand.ExecutionKind.OCR, "attempt-1", "test-call",
                "purpose", "qwen", "qwen-vl", "default-account", null, null, 0L, true, "input-fp"
        );
        RequestFactory factory = () -> HttpRequest.newBuilder()
                .uri(URI.create("http://169.254.169.254/latest/meta-data/"))
                .GET()
                .build();

        AtomicBoolean transportInvoked = new AtomicBoolean(false);
        ManagedTransport mockTransport = (req, deadline, maxBytes, cancelled) -> {
            transportInvoked.set(true);
            return new BoundedHttp.Response(200, new byte[0]);
        };

        CallOutcome outcome = service.execute(command, factory, mockTransport, () -> false);
        assertInstanceOf(CallOutcome.NotSent.class, outcome);
        CallOutcome.NotSent notSent = (CallOutcome.NotSent) outcome;
        assertTrue(notSent.reason().contains("外发请求目的地址被策略阻断"));
        assertFalse(transportInvoked.get(), "物理传输层严禁被触发调用");
    }
}
