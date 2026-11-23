package com.debugmock.psi;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.TypeConversionUtil;

/**
 * Builds JVM method descriptors / signatures from Java PSI type information.
 *
 * <p>Type parameters and wildcards are erased to their bounds (or {@code Object}), reference
 * types use internal names with {@code /} and {@code $} for nested classes, and arrays keep one
 * {@code [} per dimension. No source text / regex parsing is involved.</p>
 */
public final class JvmDescriptorUtil {

    private JvmDescriptorUtil() {
    }

    /**
     * JVM binary name of a class ({@code pkg.Outer$Inner}). {@link PsiClass#getQualifiedName()}
     * uses dots for nesting, which never matches a class file name.
     */
    public static String binaryClassName(PsiClass psiClass) {
        String qualified = psiClass.getQualifiedName();
        if (qualified == null) {
            return null;
        }
        String packageName = psiClass.getContainingFile() instanceof com.intellij.psi.PsiJavaFile
                ? ((com.intellij.psi.PsiJavaFile) psiClass.getContainingFile()).getPackageName() : "";
        String local = packageName.isEmpty() ? qualified
                : qualified.substring(Math.min(qualified.length(), packageName.length() + 1));
        return (packageName.isEmpty() ? "" : packageName + ".") + local.replace('.', '$');
    }

    /**
     * Full JVM signature, e.g. {@code (Ljava/lang/Long;)Ljava/lang/String;}.
     */
    public static String methodDescriptor(PsiMethod method) {
        StringBuilder sb = new StringBuilder("(");
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            sb.append(descriptor(parameter.getType()));
        }
        sb.append(')');
        sb.append(returnDescriptor(method));
        return sb.toString();
    }

    /**
     * Descriptor of the method return type ({@code V} for void).
     */
    public static String returnDescriptor(PsiMethod method) {
        PsiType returnType = method.getReturnType();
        return returnType == null ? "V" : descriptor(returnType);
    }

    /**
     * JVM descriptor of a single type. Generic type information is erased first.
     */
    public static String descriptor(PsiType type) {
        if (type == null) {
            return "V";
        }
        PsiType erased = TypeConversionUtil.erasure(type);
        if (erased == null) {
            erased = type;
        }
        return erasedDescriptor(erased);
    }

    private static String erasedDescriptor(PsiType type) {
        if (type instanceof PsiArrayType) {
            return "[" + descriptor(((PsiArrayType) type).getComponentType());
        }
        if (type instanceof PsiPrimitiveType) {
            return primitiveDescriptor((PsiPrimitiveType) type);
        }
        if (type instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) type;
            PsiClass resolved = classType.resolve();
            if (resolved != null) {
                return "L" + jvmInternalName(resolved) + ";";
            }
            // Unresolved reference: keep the raw canonical name as a best effort.
            String text = classType.getCanonicalText();
            int genericStart = text.indexOf('<');
            if (genericStart >= 0) {
                text = text.substring(0, genericStart);
            }
            return "L" + text.replace('.', '/') + ";";
        }
        // Defensive fallback for exotic PSI types (e.g. uninferred type variables).
        return "Ljava/lang/Object;";
    }

    private static String primitiveDescriptor(PsiPrimitiveType type) {
        switch (type.getCanonicalText()) {
            case "boolean":
                return "Z";
            case "byte":
                return "B";
            case "char":
                return "C";
            case "short":
                return "S";
            case "int":
                return "I";
            case "long":
                return "J";
            case "float":
                return "F";
            case "double":
                return "D";
            case "void":
                return "V";
            default:
                return "Ljava/lang/Object;";
        }
    }

    /**
     * JVM internal name of a class: package separators are {@code /} and nested classes use
     * {@code $}, e.g. {@code java/util/Map$Entry}.
     */
    public static String jvmInternalName(PsiClass psiClass) {
        PsiClass containing = psiClass.getContainingClass();
        if (containing == null) {
            String qualifiedName = psiClass.getQualifiedName();
            if (qualifiedName == null || qualifiedName.isEmpty()) {
                String name = psiClass.getName();
                return name == null ? "Anonymous" : name;
            }
            return qualifiedName.replace('.', '/');
        }
        String outer = jvmInternalName(containing);
        String name = psiClass.getName();
        return outer + "$" + (name == null ? "Anonymous" : name);
    }
}
