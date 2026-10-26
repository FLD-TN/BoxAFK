package com.dex.boxafk.afk;

import java.util.List;

/** A reward whose {@code %amount%} gets the rank bonus. */
public record ScaledReward(String id, double amount, List<String> commands) {

    /**
     * Whole amount to give now and the fraction carried to the next reward.
     *
     * @param carry fraction left over from the previous reward (0 <= carry < 1)
     * @return {@code [give, newCarry]}
     */
    public static double[] split(double amount, double bonusPercent, double carry) {
        double total = amount * (1 + bonusPercent / 100.0) + carry;
        // Small epsilon so 10 * 1.1 = 11.000000000000002 or 10.999999999 never loses a unit.
        double give = Math.floor(total + 1e-9);
        double rest = total - give;
        if (rest < 1e-9) rest = 0;
        return new double[]{give, rest};
    }
}
