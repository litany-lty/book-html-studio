package studio.bookhtml.api;

import org.junit.jupiter.api.Test;
import studio.bookhtml.config.LanPairingService;
import studio.bookhtml.service.ProviderResourceRegistry;
import studio.bookhtml.service.RenderBudget;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticsControllerTest {

    @Test
    void diagnosticsReportsMemoryBudgetAndExtensionsWithoutLeakingSecrets() {
        RenderBudget budget = mock(RenderBudget.class);
        when(budget.inFlightBytes()).thenReturn(1024L);
        when(budget.availablePermits()).thenReturn(3);

        BookStore store = mock(BookStore.class);
        when(store.tmpDir()).thenReturn(Path.of(System.getProperty("java.io.tmpdir", ".")));

        DiagnosticsController controller = new DiagnosticsController(budget, store);

        ProviderResourceRegistry registry = new ProviderResourceRegistry();
        controller.setProviderRegistry(registry);

        LanPairingService lan = new LanPairingService();
        controller.setLanPairingService(lan);

        Map<String, Object> diag = controller.diagnostics();
        assertNotNull(diag);
        assertTrue(diag.containsKey("heapUsedBytes"));
        assertTrue(diag.containsKey("heapMaxBytes"));
        assertEquals(1024L, diag.get("renderInFlightBytes"));
        assertEquals(3, diag.get("renderAvailablePermits"));
        assertEquals(2, diag.get("schemaVersion"));

        // Check provider metrics
        @SuppressWarnings("unchecked")
        Map<String, Object> providers = (Map<String, Object>) diag.get("providerResources");
        assertNotNull(providers);
        assertEquals(0, providers.get("qwenInFlight"));
        assertEquals(0, providers.get("ocrInFlight"));
        assertEquals(0, providers.get("minimaxInFlight"));

        // Check LAN metrics
        assertEquals(0, diag.get("lanActiveTokens"));
        assertEquals(false, diag.get("lanLocked"));

        // Ensure no credentials leaked
        String dumped = diag.toString();
        assertFalse(dumped.contains("sk-"));
        assertFalse(dumped.contains("password"));
        assertFalse(dumped.contains("token="));
    }
}
