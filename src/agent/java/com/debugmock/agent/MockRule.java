package com.debugmock.agent;

/** Minimal view of a rule as written by the plugin to .debugmock/rules.json. */
public class MockRule {
    public String id;
    public String className;
    public String methodName;
    public String signature;
    public String returnType;
    public boolean enabled = true;
    public String action = "RETURN";
    public String returnValue;
    public String exceptionClass;
    public String exceptionMessage;
    public String condition;
    public String statement;
    public int lineNumber;
    /** Normalised text of the anchored statement (resolved by the IDE, not used by ASM). */
    public String lineAnchor;
    /** 0-based index of the target statement among the method's statement-start lines. */
    public int lineOrdinal = -1;
    /** Parameter names of the target method (used to bind names in conditions). */
    public String[] parameterNames;
    /** Local variables in scope at the rule line (names + JVM descriptors). */
    public String[] localNames;
    public String[] localTypes;
    public String compiledBytecode;
    /** Full generated Java source; compiled in the application JVM when a compiler is available. */
    public String compiledSource;
    public String compiledClassName;
    /** True when the compiled block always returns / throws (older rules behave that way). */
    public boolean compiledTerminates = true;
}
