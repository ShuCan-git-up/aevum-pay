package io.aevumpay.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HmacSignerTest {

    @Test
    void signsTimestampDotBody() {
        String header = HmacSigner.header("secret", "{\"ok\":true}", Instant.ofEpochSecond(1_700_000_000L));
        String expected = HmacSigner.hmac("secret", "1700000000.{\"ok\":true}");
        assertEquals("t=1700000000,v1=" + expected, header);
        assertEquals(64, expected.length());
    }
}
