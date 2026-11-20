package com.debugmock.psi;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.TypeConversionUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The variables a rule's Java code can use.
 *
 * <p>Besides the injected helpers ({@code args}, {@code threadName}, {@code now} and — for
 * instance methods — the receiver as {@code self}), every method parameter is exposed under its
 * real name and type. The very same signature is used by the editor's code fragment (so
 * completion offers those names) and by the generated block that is compiled into the rule, so
 * what the IDE suggests is exactly what the bytecode declares.</p>
 */
public final class ExecutionContext {

    private static final String[] RESERVED = {"args", "threadName", "now", "self"};

    public static final class Param {
        public final String name;
        public final String type;

        Param(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private final String declaringClass;
    private final boolean instanceMethod;
    private final List<Param> params;
    private final List<Param> locals;
    private final String argsName;
    private final String threadNameName;
    private final String nowName;
    private final String selfName;
    private final String continueName;

    private ExecutionContext(String declaringClass, boolean instanceMethod, List<Param> params,
                             List<Param> locals,
                             String argsName, String threadNameName, String nowName, String selfName,
                             String continueName) {
        this.declaringClass = declaringClass;
        this.instanceMethod = instanceMethod;
        this.params = params;
        this.locals = locals;
        this.argsName = argsName;
        this.threadNameName = threadNameName;
        this.nowName = nowName;
        this.selfName = selfName;
        this.continueName = continueName;
    }

    public static ExecutionContext of(MethodInfo info) {
        return of(info, java.util.Collections.emptyList());
    }

    /** Like {@link #of(MethodInfo)} but also exposes the local variables in scope at the rule. */
    public static ExecutionContext of(MethodInfo info, List<LocalVariables.Local> inScope) {
        PsiMethod method = info.getMethod();
        List<Param> params = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        PsiParameter[] psiParameters = method.getParameterList().getParameters();
        for (int i = 0; i < psiParameters.length; i++) {
            PsiParameter parameter = psiParameters[i];
            String name = parameter.getName();
            if (name == null || name.isEmpty()) {
                name = "arg" + i;
            }
            params.add(new Param(name, sourceTypeText(parameter.getType(), info.getClassName())));
            used.add(name);
        }

        // Injected helper names never shadow a real parameter; when they would, a trailing
        // underscore is appended (e.g. a parameter called "args" leaves the helper as "args_").
        String argsName = unique("args", used);
        String threadNameName = unique("threadName", used);
        String nowName = unique("now", used);
        String selfName = unique("self", used);
        String continueName = unique("__dmContinue", used);

        List<Param> locals = new ArrayList<>();
        if (inScope != null) {
            for (LocalVariables.Local local : inScope) {
                // Locals keep their name unless it collides with a parameter or helper.
                locals.add(new Param(unique(local.name, used), local.type));
            }
        }

        return new ExecutionContext(info.getClassName(), !info.isStaticMethod(), params, locals,
                argsName, threadNameName, nowName, selfName, continueName);
    }

    private static String unique(String base, Set<String> used) {
        String candidate = base;
        while (used.contains(candidate)) {
            candidate = candidate + "_";
        }
        used.add(candidate);
        return candidate;
    }

    /**
     * Source-level type text of a PSI type with generics erased, e.g. {@code java.lang.String[]},
     * {@code int} or {@code com.acme.Outer.Inner}. Types that cannot be referenced from a named
     * package (default package / local classes) fall back to {@code java.lang.Object}.
     */
    public static String sourceTypeText(PsiType type, String declaringClass) {
        PsiType erased = TypeConversionUtil.erasure(type);
        if (erased == null) {
            erased = type;
        }
        if (erased instanceof PsiArrayType) {
            return sourceTypeText(((PsiArrayType) erased).getComponentType(), declaringClass) + "[]";
        }
        if (erased instanceof PsiPrimitiveType) {
            return erased.getCanonicalText();
        }
        if (erased instanceof PsiClassType) {
            PsiClass resolved = ((PsiClassType) erased).resolve();
            if (resolved != null) {
                String qualifiedName = resolved.getQualifiedName();
                if (qualifiedName != null && !qualifiedName.isEmpty()) {
                    if (qualifiedName.indexOf('.') < 0 && declaringClass.indexOf('.') >= 0) {
                        // Default-package class cannot be referenced from a named package.
                        return "java.lang.Object";
                    }
                    return qualifiedName;
                }
            }
        }
        String text = erased.getCanonicalText();
        int genericStart = text.indexOf('<');
        if (genericStart >= 0) {
            text = text.substring(0, genericStart);
        }
        if (text.isEmpty() || text.contains("$") || text.contains("<")) {
            return "java.lang.Object";
        }
        return text;
    }

    public boolean isInstanceMethod() {
        return instanceMethod;
    }

    public List<Param> getParams() {
        return params;
    }

    /** Local variables in scope at the rule line (empty for method-entry rules). */
    public List<Param> getLocals() {
        return locals;
    }

    public String getDeclaringClass() {
        return declaringClass;
    }

    public String getArgsName() {
        return argsName;
    }

    public String getThreadNameName() {
        return threadNameName;
    }

    public String getNowName() {
        return nowName;
    }

    public String getSelfName() {
        return selfName;
    }

    /**
     * Parameter list of the generated {@code __execute} method, e.g.
     * {@code Object[] args, String threadName, long now, com.acme.User self, java.lang.String name}
     * plus the trailing continue-sentinel parameter.
     */
    public String compileSignature() {
        return editorSignature() + ", Object " + continueName;
    }

    /** Same as {@link #compileSignature()} without the internal sentinel (for the editor). */
    public String editorSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append("Object[] ").append(argsName)
                .append(", String ").append(threadNameName)
                .append(", long ").append(nowName);
        if (instanceMethod) {
            sb.append(", ").append(declaringClass).append(' ').append(selfName);
        }
        for (Param param : params) {
            sb.append(", ").append(param.type).append(' ').append(param.name);
        }
        for (Param local : locals) {
            sb.append(", ").append(local.type).append(' ').append(local.name);
        }
        return sb.toString();
    }

    public String getContinueName() {
        return continueName;
    }

    /** Human-readable list of the variables offered by code completion. */
    public String variablesHint() {
        StringBuilder sb = new StringBuilder();
        sb.append(argsName).append(", ").append(threadNameName).append(", ").append(nowName);
        if (instanceMethod) {
            sb.append(", ").append(selfName);
        }
        for (Param param : params) {
            sb.append(", ").append(param.name);
        }
        for (Param local : locals) {
            sb.append(", ").append(local.name);
        }
        return sb.toString();
    }

    /**
     * A synthetic class whose {@code __execute} / {@code __condition} methods declare exactly the
     * variables above. The editor code fragments use those method bodies as their PSI context, so
     * completion resolves the parameters, the locals and the injected helpers without offering
     * anything that the generated block would not have.
     *
     * @param packageName package of the target file (may be empty)
     * @param importBlock import statements of the target file plus common JDK packages
     */
    public String contextClassSource(String packageName, String importBlock) {
        StringBuilder sb = new StringBuilder();
        if (packageName != null && !packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n");
        }
        if (importBlock != null) {
            sb.append(importBlock);
        }
        sb.append("@SuppressWarnings(\"all\")\n");
        sb.append("class DebugMockContext");
        if (declaringClass != null && !declaringClass.isEmpty()) {
            // Inherit the target class so its fields and methods resolve as they do in the method.
            sb.append(" extends ").append(declaringClass);
        }
        sb.append(" {\n");
        // Static targets get static context methods so instance fields are not mistakenly offered.
        String modifier = instanceMethod ? "  " : "  static ";
        sb.append(modifier).append("void __execute(").append(editorSignature()).append(") {}\n");
        sb.append(modifier).append("boolean __condition(").append(editorSignature())
                .append(") { return true; }\n");
        sb.append("}\n");
        return sb.toString();
    }

    public String contextClassSource() {
        return contextClassSource("", "");
    }

    /** True when the name is one of the injected helpers. */
    public static boolean isReserved(String name) {
        for (String reserved : RESERVED) {
            if (reserved.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
