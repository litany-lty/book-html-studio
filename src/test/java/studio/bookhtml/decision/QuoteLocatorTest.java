package studio.bookhtml.decision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** J02/FIX-07（T33）：重复 quote 只在唯一定位后绑定；歧义不取第一处，不删除。 */
class QuoteLocatorTest {
    @Test void verifiableOccurrenceBindsCorrectOne() {
        String original = "之乎者也之乎";
        // 第二个“之乎”（occurrenceIndex=1）
        QuoteLocator.LocateResult r = QuoteLocator.locate(original, "之乎", 1, null, null);
        assertInstanceOf(QuoteLocator.Located.class, r);
        assertEquals(4, ((QuoteLocator.Located) r).start());
        assertEquals(6, ((QuoteLocator.Located) r).end());
    }

    @Test void contextDisambiguates() {
        String original = "甲之乙丙之丁";
        QuoteLocator.LocateResult r = QuoteLocator.locate(original, "之", null, "甲", "乙");
        assertInstanceOf(QuoteLocator.Located.class, r);
        assertEquals(1, ((QuoteLocator.Located) r).start());
    }

    @Test void ambiguousKeepsRegionHintWithoutBindingFirst() {
        String original = "之乎者也之乎";
        QuoteLocator.LocateResult r = QuoteLocator.locate(original, "之", null, null, null);
        assertInstanceOf(QuoteLocator.Ambiguous.class, r);
        assertEquals(QuoteLocator.LOCATION_AMBIGUOUS, ((QuoteLocator.Ambiguous) r).code());
    }

    @Test void badOccurrenceAndContextMismatchRejected() {
        assertEquals(QuoteLocator.BAD_OCCURRENCE,
                ((QuoteLocator.Ambiguous) QuoteLocator.locate("之乎", "之", 5, null, null)).code());
        assertEquals(QuoteLocator.CONTEXT_MISMATCH,
                ((QuoteLocator.Ambiguous) QuoteLocator.locate("甲之乙", "之", null, "丙", null)).code());
        assertEquals(QuoteLocator.UNALIGNED,
                ((QuoteLocator.Ambiguous) QuoteLocator.locate("甲乙", "丙", null, null, null)).code());
    }

    @Test void surrogateSplitRejected() {
        String original = "𠀋甲";
        assertEquals(QuoteLocator.SPLIT_SURROGATE,
                ((QuoteLocator.Ambiguous) QuoteLocator.locate(original, original.substring(1, 3), 0, null, null)).code());
    }
}
