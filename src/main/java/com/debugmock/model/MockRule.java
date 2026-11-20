package com.debugmock.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A single mock rule persisted in {@code .debugmock/rules.json}.
 */
public class MockRule {

    private String id;
    private String className;
    private String methodName;
    private String signature;
    /** JVM descriptor of the return type, e.g. {@code Ljava/lang/String;} or {@code V}. */
    private String returnType;
    private boolean enabled = true;
    private MockAction action = MockAction.RETURN;
    /** Textual or JSON representation of the value to return. */
    private String returnValue;
    private String exceptionClass;
    private String exceptionMessage;
    /** Optional condition expression; empty means this rule always applies. */
    private String condition;
    /** Statement(s) to execute when action is EXECUTE, e.g. System.out.println("hit"). */
    private String statement;
    /** Source line for EXECUTE rules; 0 means method entry. */
    private int lineNumber;
    /**
     * Normalised text of the statement the rule is attached to. Together with
     * {@link #lineOrdinal} it lets the rule follow its code when lines above it are added or
     * removed, instead of staying glued to a raw line number.
     */
    private String lineAnchor;
    /** 0-based index of the target statement among the method's statement-start lines. */
    private int lineOrdinal = -1;
    /** Parameter names of the target method, so conditions can use them by name. */
    private String[] parameterNames;
    /** Names of the local variables in scope at the rule line (line rules only). */
    private String[] localNames;
    /** JVM descriptors of {@link #localNames}, in the same order. */
    private String[] localTypes;
    /** Base64 of the compiled statement class (IDE-side compilation). Null for legacy interpreter. */
    private String compiledBytecode;
    /** Full generated Java source; the app JVM compiles this at runtime when it has a JDK. */
    private String compiledSource;
    /** Fully qualified name of the compiled class. */
    private String compiledClassName;
    /** True when the compiled block always returns / throws (skips the original method body). */
    private boolean compiledTerminates = true;
    private long createdAt;
    private long updatedAt;

    public MockRule() {
    }

    public MockRule(String className, String methodName, String signature, String returnType) {
        this.id = UUID.randomUUID().toString();
        this.className = className;
        this.methodName = methodName;
        this.signature = signature;
        this.returnType = returnType;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    /** Stable identity: a UUID, so one method can hold multiple conditional rules. */
    public String key() {
        return id;
    }

    public String methodKey() {
        return className + "#" + methodName + signature;
    }

    public boolean matches(String className, String methodName, String signature) {
        return Objects.equals(this.className, className)
                && Objects.equals(this.methodName, methodName)
                && Objects.equals(this.signature, signature);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public MockAction getAction() {
        return action;
    }

    public void setAction(MockAction action) {
        this.action = action;
    }

    public String getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(String returnValue) {
        this.returnValue = returnValue;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public void setExceptionClass(String exceptionClass) {
        this.exceptionClass = exceptionClass;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public void setExceptionMessage(String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getLineAnchor() {
        return lineAnchor;
    }

    public void setLineAnchor(String lineAnchor) {
        this.lineAnchor = lineAnchor;
    }

    public int getLineOrdinal() {
        return lineOrdinal;
    }

    public void setLineOrdinal(int lineOrdinal) {
        this.lineOrdinal = lineOrdinal;
    }

    /** True when the rule carries enough information to relocate itself after source edits. */
    public boolean hasLineAnchor() {
        return lineAnchor != null && !lineAnchor.trim().isEmpty();
    }

    public String[] getParameterNames() {
        return parameterNames;
    }

    public void setParameterNames(String[] parameterNames) {
        this.parameterNames = parameterNames;
    }

    public String[] getLocalNames() {
        return localNames;
    }

    public void setLocalNames(String[] localNames) {
        this.localNames = localNames;
    }

    public String[] getLocalTypes() {
        return localTypes;
    }

    public void setLocalTypes(String[] localTypes) {
        this.localTypes = localTypes;
    }

    public String getCompiledBytecode() {
        return compiledBytecode;
    }

    public void setCompiledBytecode(String compiledBytecode) {
        this.compiledBytecode = compiledBytecode;
    }

    public String getCompiledSource() {
        return compiledSource;
    }

    public void setCompiledSource(String compiledSource) {
        this.compiledSource = compiledSource;
    }

    public String getCompiledClassName() {
        return compiledClassName;
    }

    public void setCompiledClassName(String compiledClassName) {
        this.compiledClassName = compiledClassName;
    }

    public boolean isCompiledTerminates() {
        return compiledTerminates;
    }

    public void setCompiledTerminates(boolean compiledTerminates) {
        this.compiledTerminates = compiledTerminates;
    }

    public boolean hasCondition() {
        return condition != null && !condition.trim().isEmpty();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
