package org.quickfixj.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class RawFixMessageCodecTest {
    @Test
    void replacesHumanReadableSeparators() {
        assertEquals("8=FIX.4.4\00135=D\001", RawFixMessageCodec.normalize(
                "8=FIX.4.4|35=D|"));
    }

    @Test
    void leavesSohPayloadUntouched() {
        String payload = "8=FIX.4.4\00135=D\001";
        assertSame(payload, RawFixMessageCodec.normalize(payload));
    }
}
