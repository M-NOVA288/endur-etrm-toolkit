package com.mnova.etrm.domain;

public enum BuySell {
    BUY(1),
    SELL(-1);

    private final int sign;

    BuySell(int sign) {
        this.sign = sign;
    }

    /** +1 for long (buy), -1 for short (sell). */
    public int sign() {
        return sign;
    }

    public static BuySell parse(String raw) {
        String v = raw.trim().toUpperCase();
        return switch (v) {
            case "B", "BUY" -> BUY;
            case "S", "SELL" -> SELL;
            default -> throw new IllegalArgumentException("Unknown buy/sell flag: " + raw);
        };
    }
}
