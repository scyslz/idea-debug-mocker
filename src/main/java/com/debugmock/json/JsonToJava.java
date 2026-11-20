package com.debugmock.json;

import com.debugmock.psi.ExecutionContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.InheritanceUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a JSON literal into Java statements that build the corresponding object graph, e.g.
 *
 * <pre>
 * { "id": 1, "name": "Ann", "roles": ["ADMIN"] }
 * </pre>
 * becomes
 * <pre>
 * demo.User __dm0 = new demo.User();
 * __dm0.setId(1);
 * __dm0.setName("Ann");
 * java.util.List __dm1 = new java.util.ArrayList();
 * __dm1.add(demo.Role.ADMIN);
 * __dm0.setRoles(__dm1);
 * return __dm0;
 * </pre>
 *
 * <p>Only plain beans (no-arg constructor + setter or public field), records, enums, collections,
 * maps and arrays are supported; anything else raises a clear error instead of producing
 * uncompilable code.</p>
 */
public final class JsonToJava {

    private JsonToJava() {
    }

    public static final class ConversionException extends RuntimeException {
        public ConversionException(String message) {
            super(message);
        }
    }

    public static String convert(String json, PsiType type, String declaringClass) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json.trim());
        } catch (RuntimeException e) {
            throw new ConversionException("Invalid JSON: " + e.getMessage());
        }
        StringBuilder code = new StringBuilder();
        int[] counter = {0};
        String expression = emit(root, type, declaringClass, code, counter, 0);
        code.append("return ").append(expression).append(";\n");
        return code.toString();
    }

    private static String emit(JsonElement element, PsiType type, String declaringClass,
                               StringBuilder code, int[] counter, int depth) {
        if (depth > 8) {
            throw new ConversionException("JSON nesting is too deep");
        }
        if (type == null) {
            return "null";
        }
        if (element == null || element.isJsonNull()) {
            return defaultValue(type);
        }
        if (type instanceof PsiPrimitiveType) {
            return primitive((PsiPrimitiveType) type, element);
        }
        if (JsonExample.isText(type)) {
            return quote(element.getAsString());
        }
        if (type instanceof PsiArrayType) {
            return array(element, type, declaringClass, code, counter, depth);
        }
        PsiClass resolved = type instanceof PsiClassType ? ((PsiClassType) type).resolve() : null;
        if (resolved == null) {
            return "null";
        }
        if (resolved instanceof PsiTypeParameter) {
            // An unsubstituted type variable: null is the only thing that always compiles.
            return "null";
        }
        String scalar = scalarValue(resolved, element);
        if (scalar != null) {
            return scalar;
        }
        if (isOptional(resolved)) {
            PsiType inner = typeArgument(type, 0);
            return "java.util.Optional.ofNullable("
                    + emit(element, inner, declaringClass, code, counter, depth + 1) + ")";
        }
        if (resolved.isEnum()) {
            String constant = enumConstant(resolved, element.getAsString());
            return executionContextTypeName(type, declaringClass) + "." + constant;
        }
        if (InheritanceUtil.isInheritor(resolved, "java.util.Collection")) {
            return collection(element, type, declaringClass, code, counter, depth);
        }
        if (InheritanceUtil.isInheritor(resolved, "java.util.Map")) {
            return map(element, type, declaringClass, code, counter, depth);
        }
        return object(element, type, resolved, declaringClass, code, counter, depth);
    }

    private static String primitive(PsiPrimitiveType type, JsonElement element) {
        String name = type.getCanonicalText();
        if ("boolean".equals(name)) {
            return element.getAsBoolean() ? "true" : "false";
        }
        if ("char".equals(name)) {
            String text = element.getAsString();
            if (text.isEmpty()) {
                return "'\\0'";
            }
            return "'" + escapeChar(text.charAt(0)) + "'";
        }
        String number = element.getAsString();
        if ("long".equals(name)) {
            return number + "L";
        }
        if ("float".equals(name)) {
            return number + "f";
        }
        return number;
    }

    private static String array(JsonElement element, PsiType type, String declaringClass,
                                StringBuilder code, int[] counter, int depth) {
        if (!element.isJsonArray()) {
            throw new ConversionException("Expected a JSON array for " + type.getPresentableText());
        }
        PsiType component = ((PsiArrayType) type).getComponentType();
        String componentText = executionContextTypeName(component, declaringClass);
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            values.add(emit(item, component, declaringClass, code, counter, depth + 1));
        }
        return "new " + componentText + "[]{" + String.join(", ", values) + "}";
    }

    private static String collection(JsonElement element, PsiType type, String declaringClass,
                                     StringBuilder code, int[] counter, int depth) {
        if (!element.isJsonArray()) {
            throw new ConversionException("Expected a JSON array for " + type.getPresentableText());
        }
        PsiType elementType = typeArgument(type, 0);
        String declared = executionContextTypeName(type, declaringClass);
        String implementation = InheritanceUtil.isInheritor(type, "java.util.Set")
                ? "java.util.LinkedHashSet" : "java.util.ArrayList";
        String var = "__dm" + counter[0]++;
        code.append(declared).append(' ').append(var).append(" = new ")
                .append(implementation).append("();\n");
        for (JsonElement item : element.getAsJsonArray()) {
            String value = emit(item, elementType, declaringClass, code, counter, depth + 1);
            code.append(var).append(".add(").append(value).append(");\n");
        }
        return var;
    }

    private static String map(JsonElement element, PsiType type, String declaringClass,
                              StringBuilder code, int[] counter, int depth) {
        if (!element.isJsonObject()) {
            throw new ConversionException("Expected a JSON object for " + type.getPresentableText());
        }
        PsiType keyType = typeArgument(type, 0);
        PsiType valueType = typeArgument(type, 1);
        String declared = executionContextTypeName(type, declaringClass);
        String var = "__dm" + counter[0]++;
        code.append(declared).append(' ').append(var).append(" = new java.util.LinkedHashMap();\n");
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String key = keyType != null && JsonExample.isText(keyType)
                    ? quote(entry.getKey()) : quote(entry.getKey());
            String value = emit(entry.getValue(), valueType, declaringClass, code, counter, depth + 1);
            code.append(var).append(".put(").append(key).append(", ").append(value).append(");\n");
        }
        return var;
    }

    private static String object(JsonElement element, PsiType type, PsiClass resolved,
                                 String declaringClass, StringBuilder code, int[] counter, int depth) {
        if (!element.isJsonObject()) {
            throw new ConversionException("Expected a JSON object for " + type.getPresentableText());
        }
        JsonObject json = element.getAsJsonObject();
        String name = executionContextTypeName(type, declaringClass);

        if (resolved.isInterface() || resolved.hasModifierProperty(PsiModifier.ABSTRACT)) {
            throw new ConversionException(name + " is abstract and cannot be created from JSON");
        }
        if (!isAccessible(resolved, declaringClass)) {
            throw new ConversionException(name + " is not accessible from the rule's code; "
                    + "make it public or write the Java code manually");
        }

        PsiSubstitutor substitutor = substitutorOf(type);
        if (resolved.isRecord()) {
            String var = "__dm" + counter[0]++;
            List<String> arguments = new ArrayList<>();
            for (PsiRecordComponent component : resolved.getRecordComponents()) {
                arguments.add(emit(json.get(component.getName()),
                        substituted(component.getType(), substitutor), declaringClass,
                        code, counter, depth + 1));
            }
            code.append(name).append(' ').append(var).append(" = new ").append(name)
                    .append('(').append(String.join(", ", arguments)).append(");\n");
            return var;
        }

        if (!hasNoArgConstructor(resolved)) {
            String viaConstructor = constructorCall(json, resolved, type, declaringClass, code,
                    counter, depth);
            if (viaConstructor != null) {
                return viaConstructor;
            }
            throw new ConversionException(name + " has no no-arg constructor and no constructor "
                    + "matching the JSON fields; write the Java code manually");
        }
        String var = "__dm" + counter[0]++;
        code.append(name).append(' ').append(var).append(" = new ").append(name).append("();\n");
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String fieldName = entry.getKey();
            PsiField field = resolved.findFieldByName(fieldName, true);
            if (field == null) {
                // Real-world payloads carry extra keys the Java type does not model (metadata,
                // pagination fields, ...). Ignore them just like Gson does.
                continue;
            }
            PsiType fieldType = substituted(field.getType(), substitutor);
            String value = emit(entry.getValue(), fieldType, declaringClass, code, counter, depth + 1);
            PsiMethod setter = findSetter(resolved, fieldName, fieldType, substitutor);
            if (setter != null) {
                code.append(var).append('.').append(setter.getName()).append('(')
                        .append(value).append(");\n");
            } else if (field.hasModifierProperty(PsiModifier.PUBLIC)
                    && !field.hasModifierProperty(PsiModifier.FINAL)) {
                code.append(var).append('.').append(fieldName).append(" = ").append(value).append(";\n");
            } else {
                // Lombok @Data / plain POJOs keep their fields private and PSI never sees the
                // generated setters. Set the field reflectively so the payload still converts
                // without depending on annotation processing in the rule compiler.
                code.append(setField(var, fieldName, value, counter)).append('\n');
            }
        }
        return var;
    }

    /** Builds the object through a public constructor whose parameters match the JSON keys. */
    private static String constructorCall(JsonObject json, PsiClass resolved, PsiType type,
                                          String declaringClass, StringBuilder code, int[] counter,
                                          int depth) {
        String name = executionContextTypeName(type, declaringClass);
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(json.entrySet());
        for (PsiMethod constructor : resolved.getConstructors()) {
            if (!constructor.hasModifierProperty(PsiModifier.PUBLIC)) {
                continue;
            }
            PsiParameter[] parameters = constructor.getParameterList().getParameters();
            if (parameters.length == 0 || parameters.length != entries.size()) {
                continue;
            }
            boolean byName = true;
            for (PsiParameter parameter : parameters) {
                if (parameter.getName() == null || !json.has(parameter.getName())) {
                    byName = false;
                    break;
                }
            }
            PsiSubstitutor substitutor = substitutorOf(type);
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < parameters.length; i++) {
                PsiParameter parameter = parameters[i];
                JsonElement value = byName ? json.get(parameter.getName()) : entries.get(i).getValue();
                arguments.add(emit(value, substituted(parameter.getType(), substitutor),
                        declaringClass, code, counter, depth + 1));
            }
            String var = "__dm" + counter[0]++;
            code.append(name).append(' ').append(var).append(" = new ").append(name)
                    .append('(').append(String.join(", ", arguments)).append(");\n");
            return var;
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

    /** Resolves a member type against the actual type arguments, e.g. {@code T} → {@code User}. */
    private static PsiType substituted(PsiType memberType, PsiSubstitutor substitutor) {
        if (memberType == null || substitutor == null) {
            return memberType;
        }
        try {
            PsiType result = substitutor.substitute(memberType);
            return result != null ? result : memberType;
        } catch (RuntimeException e) {
            return memberType;
        }
    }

    private static boolean isAccessible(PsiClass psiClass, String declaringClass) {
        if (psiClass.hasModifierProperty(PsiModifier.PUBLIC)) {
            return true;
        }
        if (declaringClass == null) {
            return false;
        }
        String qualified = psiClass.getQualifiedName();
        if (qualified == null) {
            return false;
        }
        return packageOf(qualified).equals(packageOf(declaringClass));
    }

    private static String packageOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(0, dot);
    }

    private static String fieldNames(PsiClass psiClass) {
        StringBuilder sb = new StringBuilder();
        for (PsiField field : psiClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(field.getName());
            if (sb.length() > 120) {
                sb.append(", ...");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Emits a reflective field assignment. Used when a JSON field maps to a private field with no
     * visible setter — most notably Lombok {@code @Data} classes, whose setters are generated by the
     * compiler and therefore never appear in PSI.
     */
    private static String setField(String target, String fieldName, String value, int[] counter) {
        int n = counter[0]++;
        String field = "__dmField" + n;
        String type = "__dmType" + n;
        String literal = quote(fieldName);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  java.lang.reflect.Field ").append(field).append(" = null;\n");
        sb.append("  for (Class<?> ").append(type).append(" = ").append(target)
                .append(".getClass(); ").append(type).append(" != null && ").append(field)
                .append(" == null; ").append(type).append(" = ").append(type)
                .append(".getSuperclass()) {\n");
        sb.append("    try { ").append(field).append(" = ").append(type)
                .append(".getDeclaredField(").append(literal).append("); } catch (NoSuchFieldException ignored) {}\n");
        sb.append("  }\n");
        sb.append("  if (").append(field).append(" != null) { ").append(field)
                .append(".setAccessible(true); ").append(field).append(".set(")
                .append(target).append(", ").append(value).append("); }\n");
        sb.append("}");
        return sb.toString();
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

    private static PsiMethod findSetter(PsiClass psiClass, String fieldName, PsiType fieldType,
                                        PsiSubstitutor substitutor) {
        String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        for (PsiMethod method : psiClass.findMethodsByName(setterName, true)) {
            if (!method.hasModifierProperty(PsiModifier.PUBLIC)) {
                continue;
            }
            PsiParameter[] parameters = method.getParameterList().getParameters();
            if (parameters.length != 1) {
                continue;
            }
            // Generic classes declare setters as setRows(List<T>): resolve T against the actual args.
            PsiType parameterType = substituted(parameters[0].getType(), substitutor);
            if (parameterType == null) {
                parameterType = parameters[0].getType();
            }
            if (parameterType.isAssignableFrom(fieldType)
                    || fieldType.isAssignableFrom(parameterType)
                    || erasure(parameterType).equals(erasure(fieldType))) {
                return method;
            }
        }
        return null;
    }

    private static PsiType erasure(PsiType type) {
        try {
            PsiType erased = com.intellij.psi.util.TypeConversionUtil.erasure(type);
            return erased == null ? type : erased;
        } catch (RuntimeException e) {
            return type;
        }
    }

    private static String enumConstant(PsiClass enumClass, String value) {
        for (PsiField field : enumClass.getFields()) {
            if (field instanceof com.intellij.psi.PsiEnumConstant && field.getName().equals(value)) {
                return value;
            }
        }
        throw new ConversionException("'" + value + "' is not a constant of " + enumClass.getName());
    }

    /**
     * Converts a JSON string/number into the usual JDK value types (dates, UUID, BigDecimal, …), so
     * a field like {@code LocalDateTime createdAt} works without the user writing the Java by hand.
     * Returns {@code null} when the type is not one of these.
     */
    private static String scalarValue(PsiClass resolved, JsonElement element) {
        String name = resolved.getQualifiedName();
        if (name == null || !element.isJsonPrimitive()) {
            return null;
        }
        String text = element.getAsString();
        switch (name) {
            case "java.time.LocalDate":
                return "java.time.LocalDate.parse(" + quote(text) + ")";
            case "java.time.LocalTime":
                return "java.time.LocalTime.parse(" + quote(text) + ")";
            case "java.time.LocalDateTime":
                return "java.time.LocalDateTime.parse(" + quote(text) + ")";
            case "java.time.Instant":
                return "java.time.Instant.parse(" + quote(text) + ")";
            case "java.time.OffsetDateTime":
                return "java.time.OffsetDateTime.parse(" + quote(text) + ")";
            case "java.time.ZonedDateTime":
                return "java.time.ZonedDateTime.parse(" + quote(text) + ")";
            case "java.time.Duration":
                return "java.time.Duration.parse(" + quote(text) + ")";
            case "java.time.Period":
                return "java.time.Period.parse(" + quote(text) + ")";
            case "java.time.YearMonth":
                return "java.time.YearMonth.parse(" + quote(text) + ")";
            case "java.time.MonthDay":
                return "java.time.MonthDay.parse(" + quote(text) + ")";
            case "java.time.Year":
                return "java.time.Year.parse(" + quote(text) + ")";
            // Boxed numbers often arrive as JSON strings ("119" instead of 119).
            case "java.lang.Integer":
            case "java.lang.Long":
            case "java.lang.Short":
            case "java.lang.Byte":
            case "java.lang.Double":
            case "java.lang.Float":
            case "java.lang.Boolean":
                return name + ".valueOf(" + quote(text) + ")";
            case "java.lang.Character":
                return "java.lang.Character.valueOf('" + escapeChar(text.charAt(0)) + "')";
            case "java.lang.Number":
                return "new java.math.BigDecimal(" + quote(text) + ")";
            case "java.math.BigDecimal":
                return "new java.math.BigDecimal(" + quote(text) + ")";
            case "java.math.BigInteger":
                return "new java.math.BigInteger(" + quote(text) + ")";
            case "java.util.UUID":
                return "java.util.UUID.fromString(" + quote(text) + ")";
            case "java.net.URI":
                return "java.net.URI.create(" + quote(text) + ")";
            case "java.util.Date":
            case "java.sql.Timestamp":
                return "new " + name + "(" + millis(element) + "L)";
            case "java.sql.Date":
                return "java.sql.Date.valueOf(" + quote(text) + ")";
            default:
                return null;
        }
    }

    private static boolean isOptional(PsiClass psiClass) {
        return "java.util.Optional".equals(psiClass.getQualifiedName());
    }

    private static String millis(JsonElement element) {
        if (element.getAsJsonPrimitive().isNumber()) {
            return element.getAsString();
        }
        try {
            return String.valueOf(java.time.Instant.parse(element.getAsString()).toEpochMilli());
        } catch (RuntimeException e) {
            throw new ConversionException("Expected epoch millis or an ISO instant, got "
                    + element.getAsString());
        }
    }

    private static String defaultValue(PsiType type) {
        if (type instanceof PsiPrimitiveType) {
            String name = ((PsiPrimitiveType) type).getCanonicalText();
            switch (name) {
                case "boolean":
                    return "false";
                case "char":
                    return "'\\0'";
                case "float":
                    return "0f";
                case "double":
                    return "0d";
                case "long":
                    return "0L";
                default:
                    return "0";
            }
        }
        return "null";
    }

    private static String executionContextTypeName(PsiType type, String declaringClass) {
        return ExecutionContext.sourceTypeText(type, declaringClass);
    }

    private static PsiType typeArgument(PsiType type, int index) {
        if (!(type instanceof PsiClassType)) {
            return null;
        }
        PsiType[] parameters = ((PsiClassType) type).getParameters();
        return index < parameters.length ? parameters[index] : null;
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String escapeChar(char c) {
        if (c == '\'') {
            return "\\'";
        }
        if (c == '\\') {
            return "\\\\";
        }
        if (c == '\n') {
            return "\\n";
        }
        return String.valueOf(c);
    }
}
