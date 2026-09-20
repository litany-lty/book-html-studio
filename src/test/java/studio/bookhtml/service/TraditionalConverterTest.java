package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraditionalConverterTest {
    @Test void convertsTraditionalPhrases(){TraditionalConverter c=new TraditionalConverter();assertEquals("软体里面有头发",c.toSimplified("軟體裡面有頭髮"));}
}
