package com.debugmock.psi;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * All information about a target Java method, obtained exclusively through the PSI API.
 */
public final class MethodInfo {

    private final PsiMethod method;
    private final String className;
    private final String methodName;
    private final String signature;
    private final String returnType;
    private final boolean voidReturn;
    private final boolean staticMethod;
    /** 1-based source line where the rule should trigger; 0 means method entry. */
    private int lineNumber;

    private MethodInfo(PsiMethod method, String className, String methodName,
                       String signature, String returnType, boolean voidReturn, boolean staticMethod) {
        this.method = method;
        this.className = className;
        this.methodName = methodName;
        this.signature = signature;
        this.returnType = returnType;
        this.voidReturn = voidReturn;
        this.staticMethod = staticMethod;
    }

    /** Builds method info from a PSI method using type information from Java PSI. */
    public static MethodInfo from(PsiMethod method) {
        String className = method.getContainingClass().getQualifiedName();
        String signature = JvmDescriptorUtil.methodDescriptor(method);
        String returnDescriptor = JvmDescriptorUtil.returnDescriptor(method);
        return new MethodInfo(
                method,
                className,
                method.getName(),
                signature,
                returnDescriptor,
                "V".equals(returnDescriptor),
                method.hasModifierProperty("static")
        );
    }

    public PsiMethod getMethod() {
        return method;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    /** Full JVM method signature, e.g. {@code (Ljava/lang/Long;)Ljava/lang/String;}. */
    public String getSignature() {
        return signature;
    }

    /** JVM descriptor of the return type, e.g. {@code Ljava/lang/String;} or {@code V}. */
    public String getReturnType() {
        return returnType;
    }

    public boolean isVoidReturn() {
        return voidReturn;
    }

    public boolean isStaticMethod() {
        return staticMethod;
    }

    /** Parameters in declaration order (name + source-level type, erased). */
    public List<PsiParameter> getParameters() {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        List<PsiParameter> result = new ArrayList<>(parameters.length);
        Collections.addAll(result, parameters);
        return result;
    }

    /** Parameter names in declaration order, used to bind names in conditions. */
    public String[] getParameterNames() {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        String[] names = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            names[i] = parameters[i].getName();
        }
        return names;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    @Override
    public String toString() {
        return className + "#" + methodName + signature;
    }
}
