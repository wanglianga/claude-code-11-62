package com.customs.ledger.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Quantity helper. All ledger quantities are stored with 6 decimal places in a material's base unit. */
public final class Qty {

    public static final int SCALE = 6;

    private Qty() {
    }

    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal parse(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return normalize(new BigDecimal(value.trim()));
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return normalize((a == null ? BigDecimal.ZERO : a).add(b == null ? BigDecimal.ZERO : b));
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return normalize((a == null ? BigDecimal.ZERO : a).subtract(b == null ? BigDecimal.ZERO : b));
    }

    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return normalize((a == null ? BigDecimal.ZERO : a).multiply(b == null ? BigDecimal.ZERO : b));
    }

    public static boolean isZero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    public static String canonical(BigDecimal value) {
        BigDecimal v = normalize(value);
        if (v.signum() == 0) {
            return "0.000000";
        }
        return v.stripTrailingZeros().toPlainString();
    }
}
