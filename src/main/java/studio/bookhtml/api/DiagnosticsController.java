package studio.bookhtml.api;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studio.bookhtml.service.RenderBudget;
import studio.bookhtml.store.BookStore;

/**
 * 阶段4：状态诊断——堆、在途预算、临时磁盘，不暴露凭据与路径细节。
 */
@RestController
@RequestMapping("/api")
public class DiagnosticsController {
    private final RenderBudget budget;
    private final BookStore store;

    public DiagnosticsController(RenderBudget budget, BookStore store) {
        this.budget = budget;
        this.store = store;
    }

    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long used = memory.getHeapMemoryUsage().getUsed();
        long max = memory.getHeapMemoryUsage().getMax();
        long tmpUsable = Long.MAX_VALUE;
        try {
            tmpUsable = Files.getFileStore(store.tmpDir()).getUsableSpace();
        } catch (Exception ignored) {
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("heapUsedBytes", used);
        result.put("heapMaxBytes", max);
        result.put("heapUsedRatio", max > 0 ? used / (double) max : 0);
        result.put("renderInFlightBytes", budget == null ? 0 : budget.inFlightBytes());
        result.put("renderAvailablePermits", budget == null ? 0 : budget.availablePermits());
        result.put("tmpUsableBytes", tmpUsable);
        result.put("schemaVersion", 2);
        return result;
    }
}
