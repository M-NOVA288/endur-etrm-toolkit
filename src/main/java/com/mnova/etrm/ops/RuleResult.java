package com.mnova.etrm.ops;

/** Outcome of one ops-service rule. BLOCK stops the status change; WARN is logged and allowed. */
public record RuleResult(String rule, Severity severity, String message) {

    public enum Severity { PASS, WARN, BLOCK }

    public static RuleResult pass(String rule) {
        return new RuleResult(rule, Severity.PASS, "ok");
    }

    public static RuleResult warn(String rule, String message) {
        return new RuleResult(rule, Severity.WARN, message);
    }

    public static RuleResult block(String rule, String message) {
        return new RuleResult(rule, Severity.BLOCK, message);
    }

    public boolean isBlocking() {
        return severity == Severity.BLOCK;
    }
}
