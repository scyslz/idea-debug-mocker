package com.debugmock.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.PsiWhiteSpace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local variables that are in scope at a given source line of a method body.
 *
 * <p>They are treated exactly like the method's parameters: declared in the generated code block
 * (so the block compiles) and passed by the agent at the instrumentation point (so the values are
 * the real ones). The editor's completion context declares them too, so what the IDE suggests is
 * what actually works.</p>
 */
public final class LocalVariables {

    /** One local variable: its source type text and the JVM descriptor used by the agent. */
    public static final class Local {
        public final String name;
        public final String type;
        public final String descriptor;

        Local(String name, String type, String descriptor) {
            this.name = name;
            this.type = type;
            this.descriptor = descriptor;
        }
    }

    private LocalVariables() {
    }

    /** Locals in scope at the 1-based {@code line}; empty when the line is unknown. */
    public static List<Local> inScope(PsiMethod method, int line) {
        if (method == null || method.getBody() == null || line <= 0) {
            return new ArrayList<>();
        }
        PsiFile file = method.getContainingFile();
        if (file == null) {
            return new ArrayList<>();
        }
        Project project = file.getProject();
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null || document.getLineCount() == 0) {
            return new ArrayList<>();
        }
        int lineIndex = Math.max(0, Math.min(line - 1, document.getLineCount() - 1));
        int offset = document.getLineStartOffset(lineIndex);

        PsiElement anchor = firstNonWhitespace(file, document, lineIndex);
        if (anchor == null) {
            return new ArrayList<>();
        }
        Set<PsiElement> ancestors = new HashSet<>();
        for (PsiElement e = anchor; e != null; e = e.getParent()) {
            ancestors.add(e);
        }

        String declaringClass = method.getContainingClass() == null
                ? "" : method.getContainingClass().getQualifiedName();
        Map<String, Local> byName = new LinkedHashMap<>();
        PsiCodeBlock body = method.getBody();
        for (PsiLocalVariable variable : PsiTreeUtil.findChildrenOfType(body, PsiLocalVariable.class)) {
            String name = variable.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (variable.getTextRange().getEndOffset() > offset) {
                continue; // declared after the rule line
            }
            PsiElement scope = scopeContainer(variable);
            if (scope == null || !ancestors.contains(scope)) {
                continue; // declared in a sibling block: not visible here
            }
            PsiType type = variable.getType();
            String text = ExecutionContext.sourceTypeText(type, declaringClass);
            byName.put(name, new Local(name, text, descriptor(type)));
        }
        return new ArrayList<>(byName.values());
    }

    private static PsiElement scopeContainer(PsiLocalVariable variable) {
        PsiElement parent = variable.getParent();
        if (parent instanceof PsiDeclarationStatement) {
            return parent.getParent();
        }
        return parent;
    }

    private static PsiElement firstNonWhitespace(PsiFile file, Document document, int line) {
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        for (int offset = start; offset < end; offset++) {
            PsiElement element = file.findElementAt(offset);
            if (element != null && !(element instanceof PsiWhiteSpace)) {
                return element;
            }
        }
        return null;
    }

    /** JVM descriptor of a type, e.g. {@code Ljava/lang/String;}, {@code I}, {@code [I}. */
    public static String descriptor(PsiType type) {
        if (type instanceof PsiPrimitiveType) {
            return primitiveDescriptor(((PsiPrimitiveType) type).getCanonicalText());
        }
        if (type instanceof PsiArrayType) {
            return "[" + descriptor(((PsiArrayType) type).getComponentType());
        }
        if (type instanceof PsiClassType) {
            PsiClass resolved = ((PsiClassType) type).resolve();
            if (resolved != null) {
                String qualifiedName = resolved.getQualifiedName();
                if (qualifiedName != null && !qualifiedName.isEmpty()) {
                    return "L" + qualifiedName.replace('.', '/') + ";";
                }
            }
        }
        String text = type.getCanonicalText();
        int genericStart = text.indexOf('<');
        if (genericStart >= 0) {
            text = text.substring(0, genericStart);
        }
        if (!text.isEmpty() && !text.contains("$")) {
            return "L" + text.replace('.', '/') + ";";
        }
        return "Ljava/lang/Object;";
    }

    private static String primitiveDescriptor(String name) {
        switch (name) {
            case "boolean": return "Z";
            case "byte": return "B";
            case "char": return "C";
            case "short": return "S";
            case "int": return "I";
            case "long": return "J";
            case "float": return "F";
            case "double": return "D";
            case "void": return "V";
            default: return "Ljava/lang/Object;";
        }
    }
}
