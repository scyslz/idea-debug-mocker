package com.debugmock.json;

import com.debugmock.psi.ExecutionContext;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.InheritanceUtil;

import java.util.HashSet;
import java.util.Set;

/**
 * Builds a JSON skeleton for a Java return type, so a RETURN rule for an object can be filled in
 * by hand. Example for {@code User{int id; String name; List<Role> roles;}}:
 *
 * <pre>
 * {
 *   "id": 0,
 *   "name": "",
 *   "roles": [
 *     {
 *       "code": ""
 *     }
 *   ]
 * }
 * </pre>
 */
public final class JsonExample {

    private static final int MAX_DEPTH = 3;

    /** Sample JSON strings for the JDK value types the converter knows how to parse. */
    private static final java.util.Map<String, String> TEXT_VALUES = new java.util.HashMap<>();

    /** JDK value types whose JSON representation is a plain number. */
    private static final Set<String> NUMERIC_VALUES = new HashSet<>();

    static {
        TEXT_VALUES.put("java.time.LocalDate", "2024-01-01");
        TEXT_VALUES.put("java.time.LocalTime", "00:00:00");
        TEXT_VALUES.put("java.time.LocalDateTime", "2024-01-01T00:00:00");
        TEXT_VALUES.put("java.time.Instant", "2024-01-01T00:00:00Z");
        TEXT_VALUES.put("java.time.OffsetDateTime", "2024-01-01T00:00:00+00:00");
        TEXT_VALUES.put("java.time.ZonedDateTime", "2024-01-01T00:00:00Z");
        TEXT_VALUES.put("java.time.Duration", "PT0S");
        TEXT_VALUES.put("java.time.Period", "P0D");
        TEXT_VALUES.put("java.time.YearMonth", "2024-01");
        TEXT_VALUES.put("java.time.MonthDay", "--01-01");
        TEXT_VALUES.put("java.time.Year", "2024");
        TEXT_VALUES.put("java.util.UUID", "00000000-0000-0000-0000-000000000000");
        TEXT_VALUES.put("java.net.URI", "https://example.com");
        TEXT_VALUES.put("java.sql.Date", "2024-01-01");
        NUMERIC_VALUES.add("java.math.BigDecimal");
        NUMERIC_VALUES.add("java.math.BigInteger");
        NUMERIC_VALUES.add("java.util.Date");
        NUMERIC_VALUES.add("java.sql.Timestamp");
    }

    private JsonExample() {
    }

    /** True when generating a JSON example makes sense for this type. */
    public static boolean supports(PsiType type) {
        return type != null && !(type instanceof PsiPrimitiveType) && !isText(type);
    }

    public static String forType(PsiType type, String declaringClass) {
        StringBuilder sb = new StringBuilder();
        write(type, declaringClass, sb, 0, new HashSet<>());
        return sb.toString();
    }

    public static boolean isText(PsiType type) {
        if (!(type instanceof PsiClassType)) {
            return false;
        }
        PsiClass resolved = ((PsiClassType) type).resolve();
        if (resolved == null) {
            return false;
        }
        String name = resolved.getQualifiedName();
        return "java.lang.String".equals(name) || "java.lang.Character".equals(name)
                || "java.lang.CharSequence".equals(name);
    }

    private static void write(PsiType type, String declaringClass, StringBuilder sb, int indent,
                              Set<PsiClass> stack) {
        if (type instanceof PsiPrimitiveType) {
            String name = ((PsiPrimitiveType) type).getCanonicalText();
            switch (name) {
                case "boolean":
                    sb.append("false");
                    return;
                case "char":
                    sb.append("\"\"");
                    return;
                case "float":
                case "double":
                    sb.append("0.0");
                    return;
                case "long":
                    sb.append("0");
                    return;
                case "byte":
                case "short":
                case "int":
                    sb.append("0");
                    return;
                default:
                    sb.append("null");
                    return;
            }
        }
        if (isText(type)) {
            sb.append("\"\"");
            return;
        }
        if (type instanceof PsiArrayType) {
            writeArray(((PsiArrayType) type).getComponentType(), declaringClass, sb, indent, stack);
            return;
        }
        PsiClass resolved = type instanceof PsiClassType ? ((PsiClassType) type).resolve() : null;
        if (resolved == null) {
            sb.append("{}");
            return;
        }
        if (resolved instanceof PsiTypeParameter) {
            sb.append("null");
            return;
        }
        if (resolved.isEnum()) {
            String constant = firstEnumConstant(resolved);
            sb.append(constant == null ? "\"\"" : "\"" + constant + "\"");
            return;
        }
        if (InheritanceUtil.isInheritor(resolved, "java.util.Collection")) {
            PsiType element = typeArgument(type, 0);
            writeArray(element, declaringClass, sb, indent, stack);
            return;
        }
        if (InheritanceUtil.isInheritor(resolved, "java.util.Map")) {
            PsiType value = typeArgument(type, 1);
            sb.append("{\n");
            pad(sb, indent + 1);
            sb.append("\"key\": ");
            write(value, declaringClass, sb, indent + 1, stack);
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
            return;
        }
        String qualified = resolved.getQualifiedName();
        String textValue = qualified == null ? null : TEXT_VALUES.get(qualified);
        if (textValue != null) {
            sb.append('"').append(textValue).append('"');
            return;
        }
        if (NUMERIC_VALUES.contains(qualified)) {
            sb.append('0');
            return;
        }
        if ("java.util.Optional".equals(qualified)) {
            PsiType inner = typeArgument(type, 0);
            if (inner == null) {
                sb.append("null");
            } else {
                write(inner, declaringClass, sb, indent, stack);
            }
            return;
        }
        if (stack.contains(resolved) || stack.size() >= MAX_DEPTH) {
            sb.append("null");
            return;
        }
        stack.add(resolved);
        writeObject(resolved, type, declaringClass, sb, indent, stack);
        stack.remove(resolved);
    }

    private static void writeArray(PsiType element, String declaringClass, StringBuilder sb,
                                   int indent, Set<PsiClass> stack) {
        if (element == null) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        pad(sb, indent + 1);
        write(element, declaringClass, sb, indent + 1, stack);
        sb.append('\n');
        pad(sb, indent);
        sb.append(']');
    }

    private static void writeObject(PsiClass psiClass, PsiType declaredType, String declaringClass,
                                    StringBuilder sb, int indent, Set<PsiClass> stack) {
        sb.append("{\n");
        boolean first = true;
        PsiSubstitutor substitutor = substitutorOf(declaredType);
        for (PsiRecordComponent component : psiClass.getRecordComponents()) {
            first = writeEntry(sb, indent, first, component.getName(),
                    substituted(component.getType(), substitutor), declaringClass, stack);
        }
        for (PsiField field : psiClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)
                    || field.hasModifierProperty(PsiModifier.TRANSIENT)
                    || field.getName().startsWith("this$")
                    || field.getName().startsWith("$")) {
                continue;
            }
            // The agent deserializes with Gson, which sets private fields reflectively — Lombok
            // @Data POJOs have no setters in PSI but are perfectly valid targets. List every field.
            first = writeEntry(sb, indent, first, field.getName(),
                    substituted(field.getType(), substitutor), declaringClass, stack);
        }
        if (!first) {
            sb.append('\n');
            pad(sb, indent);
        }
        sb.append('}');
    }

    private static boolean writeEntry(StringBuilder sb, int indent, boolean first, String name,
                                      PsiType type, String declaringClass, Set<PsiClass> stack) {
        if (!first) {
            sb.append(",\n");
        }
        pad(sb, indent + 1);
        sb.append('"').append(name).append("\": ");
        write(type, declaringClass, sb, indent + 1, stack);
        return false;
    }

    private static String firstEnumConstant(PsiClass enumClass) {
        PsiField[] fields = enumClass.getFields();
        for (PsiField field : fields) {
            if (field instanceof com.intellij.psi.PsiEnumConstant) {
                return field.getName();
            }
        }
        return null;
    }

    private static PsiSubstitutor substitutorOf(PsiType type) {
        if (type instanceof PsiClassType) {
            try {
                return ((PsiClassType) type).resolveGenerics().getSubstitutor();
            } catch (RuntimeException e) {
                return PsiSubstitutor.EMPTY;
            }
        }
        return PsiSubstitutor.EMPTY;
    }

    private static PsiType substituted(PsiType memberType, PsiSubstitutor substitutor) {
        if (memberType == null) {
            return null;
        }
        try {
            PsiType result = substitutor.substitute(memberType);
            return result != null ? result : memberType;
        } catch (RuntimeException e) {
            return memberType;
        }
    }

    private static boolean hasNoArgConstructor(PsiClass psiClass) {
        if (psiClass.getConstructors().length == 0) {
            return true; // implicit default constructor
        }
        for (PsiMethod constructor : psiClass.getConstructors()) {
            if (constructor.getParameterList().getParametersCount() == 0) {
                return true;
            }
        }
        return false;
    }

    /** True when the generated Java can assign this field (public setter or public non-final field). */
    private static boolean settable(PsiClass owner, PsiField field) {
        if (field.hasModifierProperty(PsiModifier.PUBLIC)
                && !field.hasModifierProperty(PsiModifier.FINAL)) {
            return true;
        }
        String name = field.getName();
        if (name.isEmpty()) {
            return false;
        }
        String setterName = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (PsiMethod method : owner.findMethodsByName(setterName, true)) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC)
                    && method.getParameterList().getParametersCount() == 1) {
                return true;
            }
        }
        return false;
    }

    private static PsiType typeArgument(PsiType type, int index) {
        if (!(type instanceof PsiClassType)) {
            return null;
        }
        PsiType[] parameters = ((PsiClassType) type).getParameters();
        return index < parameters.length ? parameters[index] : null;
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    /** Kept for callers that only need the type name. */
    public static String typeText(PsiType type, String declaringClass) {
        return ExecutionContext.sourceTypeText(type, declaringClass);
    }
}
