package com.debugmock.model;

/**
 * The action a mock rule performs when the target method is intercepted.
 */
public enum MockAction {
    /** Return a configured value. */
    RETURN,
    /** Throw a configured exception. */
    THROW,
    /** Skip the original method body (only valid for void methods). */
    SKIP,
    /** Execute a statement automatically at method entry, then continue the original body. */
    EXECUTE;

    public static MockAction parse(String value) {
        if (value == null) {
            return RETURN;
        }
        for (MockAction action : values()) {
            if (action.name().equalsIgnoreCase(value.trim())) {
                return action;
            }
        }
        return RETURN;
    }
}
