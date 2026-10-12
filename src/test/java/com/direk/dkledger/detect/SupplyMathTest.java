package com.direk.dkledger.detect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SupplyMathTest {

    @Test
    void statistics() {
        assertEquals(0, SupplyMath.gini(new double[]{10, 10, 10, 10}), 1e-9);
        assertEquals(0.75, SupplyMath.gini(new double[]{0, 0, 0, 100}), 1e-9);
        assertEquals(0.5, SupplyMath.topShare(new double[]{50, 10, 10, 10, 10, 5, 5, 0, 0, 0}, 0.10), 1e-9);
        assertEquals(15, SupplyMath.median(new double[]{30, 10, 20, 5}), 1e-9);
        assertEquals(0.10, SupplyMath.dailyGrowth(100, 121, 2), 1e-9);
    }
}
