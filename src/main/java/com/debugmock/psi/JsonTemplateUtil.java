package com.debugmock.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.TypeConversionUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a JSON skeleton for an object return type from its PSI fields, so the user only has to
 * fill in the values instead of writing the whole structure by hand.
 */
public final class JsonTemplateUtil {

    private static final int MAX_DEPTH = 3;

    private JsonTemplateUtil() {
    }

    public static String suggestJson(String returnDescriptor, Project project) {
        if (returnDescriptor == null || !returnDescriptor.startsWith("L")
                || !returnDescriptor.endsWith(";")) {
            return "";
        }
        String fqcn = returnDescriptor.substring(1, returnDescriptor.length() - 1).replace('/', '.');
        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(fqcn, GlobalSearchScope.allScope(project));
        if (psiClass == null) {
            return "{}";
        }
        Object value = valueFor(psiClass, 0);
        return toJson(value, 0);
    }

    private static Object valueFor(PsiClass psiClass, int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (PsiField field : psiClass.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)
                    || field.hasModifierProperty(PsiModifier.TRANSIENT)) {
                continue;
            }
            map.put(field.getName(), valueFor(field.getType(), depth + 1));
        }
        return map;
    }

    private static Object valueFor(PsiType type, int depth) {
        PsiType erased = TypeConversionUtil.erasure(type);
        if (erased == null) {
            erased = type;
        }
        if (erased instanceof PsiArrayType) {
            return new Object[0];
        }
        if (erased instanceof PsiPrimitiveType) {
            String name = erased.getCanonicalText();
            switch (name) {
                case "boolean": return false;
                case "char": return "";
                case "byte":
                case "short":
                case "int":
                case "long":
                case "float":
                case "double": return 0;
                default: return null;
            }
        }
        if (erased instanceof PsiClassType) {
            String name = ((PsiClassType) erased).getCanonicalText();
            if (name.equals("java.lang.String") || name.equals("java.lang.Character")) {
                return "";
            }
            if (isCollection(name)) {
                return new Object[0];
            }
            if (isMap(name)) {
                return new LinkedHashMap<String, Object>();
            }
            if (depth < MAX_DEPTH) {
                PsiClass resolved = ((PsiClassType) erased).resolve();
                if (resolved != null && !resolved.isEnum()) {
                    return valueFor(resolved, depth + 1);
                }
            }
        }
        return null;
    }

    private static boolean isCollection(String name) {
        return name.startsWith("java.util.List") || name.startsWith("java.util.Set")
                || name.startsWith("java.util.Collection") || name.startsWith("java.util.Queue")
                || name.startsWith("java.util.stream.");
    }

    private static boolean isMap(String name) {
        return name.startsWith("java.util.Map") || name.startsWith("java.util.HashMap");
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object value, int indent) {
        StringBuilder sb = new StringBuilder();
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + escape((String) value) + "\"";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof Object[]) {
            return "[]";
        }
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.isEmpty()) {
                return "{}";
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                indent(sb, indent + 1);
                sb.append('"').append(escape(entry.getKey())).append("\": ")
                        .append(toJson(entry.getValue(), indent + 1));
                if (++i < map.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            indent(sb, indent);
            sb.append('}');
            return sb.toString();
        }
        return "null";
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
