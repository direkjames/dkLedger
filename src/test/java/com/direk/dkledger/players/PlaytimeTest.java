package com.direk.dkledger.players;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaytimeTest {

    @Test
    void minutesAndJoinsPerDay() {
        UUID a = UUID.randomUUID();
        PlaytimeBuffer b = new PlaytimeBuffer();
        b.joined("2026-10-13", a);
        for (int i = 0; i < 30; i++) b.minute("2026-10-13", a, false);
        for (int i = 0; i < 12; i++) b.minute("2026-10-13", a, true);
        b.minute("2026-10-14", a, false); // played past midnight
        var days = b.drain();
        assertEquals(2, days.size());
        assertEquals(30, days.getFirst().activeMinutes());
        assertEquals(12, days.getFirst().afkMinutes());
        assertEquals(1, days.getFirst().sessions());
        assertEquals(0, days.get(1).sessions());
        assertTrue(b.drain().isEmpty());
    }

    @Test
    void progressEventsAreTidied() {
        ProgressEvent e = new ProgressEvent(0, UUID.randomUUID(), null, "AOneBlock", " OneBlock Phase ", "plains", Double.NaN);
        assertEquals("oneblock-phase", e.type());
        assertEquals("", e.name());
        assertEquals(0, e.value(), 1e-9);
    }
}
