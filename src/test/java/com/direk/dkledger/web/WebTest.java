package com.direk.dkledger.web;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebTest {

    @Test
    void jsonIsEscapedAndNumbersAreClean() {
        String json = Json.write(Json.obj("name", "Steve \"<b>\"\n", "n", 3.0, "x", 0.123456789, "nan", Double.NaN,
                "list", List.of(1, true), "none", null));
        assertEquals("{\"name\":\"Steve \\\"\\u003cb\\u003e\\\"\\n\",\"n\":3,\"x\":0.1235,\"nan\":null,\"list\":[1,true],\"none\":null}", json);
        assertEquals("[]", Json.write(Arrays.asList()));
    }

    @Test
    void loginKeysWorkOnceAndExpire() {
        UUID a = UUID.randomUUID();
        WebSessions s = new WebSessions(5 * 60_000L, 3_600_000L);
        String key = s.issueKey(a, "direk", 0);
        assertTrue(key.length() >= 40, "long random key");
        String token = s.redeem(key, 1_000).orElseThrow();
        assertFalse(s.redeem(key, 1_000).isPresent(), "a key works once");
        assertEquals("direk", s.session(token, 2_000).orElseThrow().name());
        assertFalse(s.session(token, 3_600_001L + 1_000).isPresent(), "sessions run out");

        String old = s.issueKey(a, "direk", 0);
        assertFalse(s.redeem(old, 5 * 60_000L + 1).isPresent(), "keys run out after 5 minutes");

        String first = s.issueKey(a, "direk", 0);
        String second = s.issueKey(a, "direk", 0);
        assertFalse(s.redeem(first, 10).isPresent(), "a new key replaces the old one");
        assertTrue(s.redeem(second, 10).isPresent());

        assertFalse(s.redeem("not-a-key", 0).isPresent());
        assertFalse(s.session(null, 0).isPresent());
    }

    @Test
    void signingOut() {
        UUID a = UUID.randomUUID();
        WebSessions s = new WebSessions(60_000L, 3_600_000L);
        String t1 = s.redeem(s.issueKey(a, "a", 0), 0).orElseThrow();
        String t2 = s.redeem(s.issueKey(null, "console", 0), 0).orElseThrow();
        s.logout(a);
        assertFalse(s.session(t1, 1).isPresent(), "one person's sessions end");
        assertTrue(s.session(t2, 1).isPresent());
        s.issueKey(a, "a", 0);
        assertEquals(1, s.logoutAll());
        assertFalse(s.session(t2, 1).isPresent());
        assertEquals(0, s.active(1));
    }
}
