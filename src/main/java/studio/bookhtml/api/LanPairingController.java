package studio.bookhtml.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import studio.bookhtml.config.LanPairingService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * G13 / B11: 局域网配对与状态控制器。
 * 提供局域网设备配对凭证换取、状态查询、运维 PIN 码重置与凭据撤销。
 */
@RestController
@RequestMapping("/api/lan")
public class LanPairingController {
    private final LanPairingService pairingService;

    public LanPairingController(LanPairingService pairingService) {
        this.pairingService = pairingService;
    }

    public record PairRequest(String pin) {}

    @PostMapping("/pair")
    public ResponseEntity<Map<String, Object>> pair(
            HttpServletRequest request,
            @RequestBody(required = false) PairRequest body) {
        String pin = body != null ? body.pin() : request.getParameter("pin");
        LanPairingService.PairingResult result = pairingService.pair(pin, request.getRemoteAddr());

        Map<String, Object> resp = new LinkedHashMap<>();
        if (result.success()) {
            resp.put("success", true);
            resp.put("token", result.token());
            resp.put("capabilities", result.capabilities());
            resp.put("message", result.message());
            return ResponseEntity.ok(resp);
        } else {
            resp.put("success", false);
            resp.put("message", result.message());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(resp);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        LanPairingService.AccessMode mode = pairingService.determineAccessMode(request);
        String token = LanPairingService.extractToken(request);
        boolean paired = token != null && pairingService.isValidToken(token);
        Set<LanPairingService.LanCapability> capabilities = mode == LanPairingService.AccessMode.LOOPBACK
                ? Set.of(LanPairingService.LanCapability.values())
                : pairingService.getCapabilities(token);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accessMode", mode.name());
        resp.put("isLoopback", mode == LanPairingService.AccessMode.LOOPBACK);
        resp.put("isPaired", paired || mode == LanPairingService.AccessMode.LOOPBACK);
        resp.put("capabilities", capabilities);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/pin")
    public ResponseEntity<Map<String, Object>> generatePin(HttpServletRequest request) {
        if (!pairingService.isAllowed(request, LanPairingService.LanCapability.MANAGE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅限本机或管理员生成配对码");
        }
        String pin = pairingService.generateNewPin();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pin", pin);
        resp.put("message", "新配对码已生成，有效期 10 分钟");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/revoke")
    public ResponseEntity<Map<String, Object>> revoke(HttpServletRequest request) {
        if (!pairingService.isAllowed(request, LanPairingService.LanCapability.MANAGE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅限本机或管理员撤销凭据");
        }
        String token = LanPairingService.extractToken(request);
        if (token != null) {
            pairingService.revokeToken(token);
        } else {
            pairingService.revokeAll();
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "配对凭据已撤销");
        return ResponseEntity.ok(resp);
    }
}
