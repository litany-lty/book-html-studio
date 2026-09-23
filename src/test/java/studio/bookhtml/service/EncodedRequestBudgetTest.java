package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EncodedRequestBudgetTest {

    @Test
    void encodedByteBudgetTracksAndGrowsWithinLimit() throws Exception {
        long maxBytes = 1024 * 1024; // 1 MB
        ResourceBudgetManager manager = new ResourceBudgetManager(2, maxBytes, 5_000);

        byte[] initial = new byte[200 * 1024]; // 200 KB
        EncodedImageArtifact artifact = manager.wrapEncoded(initial, "image/png");
        assertEquals(200 * 1024, manager.inFlightEncodedBytes());

        ResourceBudgetManager.Ticket ticket = manager.acquireEncodedBytes(100 * 1024, () -> false);
        assertEquals(300 * 1024, manager.inFlightEncodedBytes());

        // Try grow within limit
        boolean grown = manager.tryGrowEncoded(ticket, 200 * 1024);
        assertTrue(grown);
        assertEquals(500 * 1024, manager.inFlightEncodedBytes());

        // Try grow beyond remaining capacity (remaining is 1024 - 500 = 524 KB, ask for 600 KB)
        boolean exceed = manager.tryGrowEncoded(ticket, 600 * 1024);
        assertFalse(exceed, "Growing beyond maxInFlightBytes must be rejected");
        assertEquals(500 * 1024, manager.inFlightEncodedBytes());

        ticket.close();
        assertEquals(200 * 1024, manager.inFlightEncodedBytes());

        artifact.close();
        assertEquals(0, manager.inFlightEncodedBytes());
    }

    @Test
    void base64SizeCalculationEstimatesWorstCasePayload() {
        byte[] raw = new byte[100];
        String b64 = Base64.getEncoder().encodeToString(raw);
        // Base64 expansion formula: 4 * ceil(n / 3)
        int expectedChars = 4 * (int) Math.ceil(raw.length / 3.0);
        assertEquals(expectedChars, b64.length());

        // UTF-16 in Java char (2 bytes per char) + JSON UTF-8 serialization
        long estimatedMemory = (long) expectedChars * 2L + (long) b64.getBytes().length;
        assertTrue(estimatedMemory > raw.length);
    }
}
