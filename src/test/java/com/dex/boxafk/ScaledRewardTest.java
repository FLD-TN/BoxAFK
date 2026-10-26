package com.dex.boxafk;

import com.dex.boxafk.afk.ScaledReward;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScaledRewardTest {

    @Test
    void noBonusGivesAmount() {
        double[] r = ScaledReward.split(10, 0, 0);
        assertEquals(10, r[0]);
        assertEquals(0, r[1], 1e-9);
    }

    @Test
    void tenPercentOfTenIsEleven() {
        double[] r = ScaledReward.split(10, 10, 0);
        assertEquals(11, r[0]);
        assertEquals(0, r[1], 1e-9);
    }

    @Test
    void fractionsCarryOver() {
        double carry = 0;
        long total = 0;
        for (int i = 0; i < 10; i++) {
            double[] r = ScaledReward.split(10, 15, carry);
            total += (long) r[0];
            carry = r[1];
        }
        assertEquals(115, total);
    }
}
