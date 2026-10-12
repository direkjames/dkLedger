package com.direk.dkledger.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MoneyTest {

    @Test
    void findsMoneyInRewardCommands() {
        assertEquals(500, Money.givenBy("eco give %player% 500").orElseThrow(), 0.001);
        assertEquals(1000, Money.givenBy("[console] eco give <player> 1,000").orElseThrow(), 0.001);
        assertEquals(25.5, Money.givenBy("/money give {player} 25.5").orElseThrow(), 0.001);
        assertEquals(450, Money.givenBy("eco give %player_name% 450").orElseThrow(), 0.001);
        assertEquals(10, Money.givenBy("essentials:eco add Steve 10").orElseThrow(), 0.001);
    }

    @Test
    void ignoresCommandsThatDontGiveMoney() {
        assertFalse(Money.givenBy("eco take %player% 500").isPresent());
        assertFalse(Money.givenBy("give %player% diamond 8").isPresent());
        assertFalse(Money.givenBy("economy give %player% %amount%").isPresent());
        assertFalse(Money.givenBy("crates key give %player% vote 1").isPresent());
    }

    @Test
    void countsLuckChances() {
        assertEquals(0.1, Money.luckChance("[luck] {10} eco give <player> 100"), 0.0001);
        assertEquals(1, Money.luckChance("[console] eco give <player> 100"), 0.0001);
        assertEquals(1000 + 10, Money.givenByAll(List.of("[console] eco give <player> 1000",
                "[luck] {10} eco give <player> 100", "[message] hi")), 0.001);
    }

    @Test
    void formatsAmounts() {
        assertEquals("$1,234", Money.format(1234.4));
        assertEquals("$12.50", Money.format(12.5));
        assertEquals("-$3.00", Money.format(-3));
        assertEquals("10 min", Money.duration(600));
        assertEquals("120h (5 days)", Money.hours(120));
    }
}
