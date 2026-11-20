package com.debugmock.psi;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.util.PsiTreeUtil;

/**
 * Resolves the Java method targeted by the current editor caret / selection using the PSI tree.
 */
public final class MethodResolver {

    private MethodResolver() {
    }

    public static MethodInfo resolve(AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return null;
        }
        return resolve(project, editor);
    }

    public static MethodInfo resolve(Project project, Editor editor) {
        // AnAction.update() / actionPerformed() and gutter clicks all run on the EDT. The PSI
        // traversal below resolves types (MethodInfo.from -> JVM descriptors -> PsiClassType
        // .resolve()), which requires read access on 2026.3+. Hold a read action for the whole
        // resolution so none of those calls log a ThreadingAssertions error.
        return com.intellij.openapi.application.ReadAction.compute(
                () -> resolveUnderReadAction(project, editor));
    }

    private static MethodInfo resolveUnderReadAction(Project project, Editor editor) {
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (!(psiFile instanceof PsiJavaFile)) {
            return null;
        }

        SelectionModel selection = editor.getSelectionModel();
        PsiMethod method = fromSelection(psiFile, selection);
        if (method == null) {
            method = fromCaret(psiFile, editor.getCaretModel());
        }
        if (method == null || method.isConstructor()) {
            return null;
        }
        if (method.getContainingClass() == null || method.getContainingClass().getQualifiedName() == null) {
            return null;
        }
        MethodInfo info;
        try {
            info = MethodInfo.from(method);
        } catch (RuntimeException e) {
            // Unresolved types (still indexing, missing dependency) must not break the menu.
            return null;
        }
        com.intellij.openapi.editor.Document document =
                PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document != null) {
            int offset = selection.hasSelection()
                    ? selection.getSelectionStart() : editor.getCaretModel().getOffset();
            int caret = Math.min(offset, document.getTextLength());
            int caretLine = document.getLineNumber(caret) + 1;
            info.setLineNumber(targetLine(psiFile, document, method, caret, caretLine));
        }
        return info;
    }

    /**
     * Maps the caret line to the line a rule can run on: {@code 0} (method entry) when the caret is
     * on the signature, otherwise the enclosing statement's first line — a rule stored for a
     * signature line would never be reached by the agent.
     */
    private static int targetLine(PsiFile psiFile, com.intellij.openapi.editor.Document document,
                                  PsiMethod method, int caretOffset, int caretLine) {
        com.intellij.psi.PsiCodeBlock body = method.getBody();
        if (body == null) {
            return 0;
        }
        // Anywhere in the signature / parameter list means "at method entry".
        if (caretOffset < body.getTextRange().getStartOffset()) {
            return 0;
        }
        int statementLine = LineTargets.normalize(psiFile, document, method, caretLine);
        return Math.max(statementLine, 0);
    }

    /** Resolves the PSI method of an existing rule (for editing from the tool window / gutter). */
    public static MethodInfo resolveByRule(Project project, String className,
                                           String methodName, String signature) {
        // Called from UI click handlers, so the index lookup needs its own read action.
        return com.intellij.openapi.application.ReadAction.compute(() -> {
            PsiClass psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.allScope(project));
            if (psiClass == null) {
                return null;
            }
            for (PsiMethod method : psiClass.findMethodsByName(methodName, false)) {
                if (!method.isConstructor() && signature.equals(JvmDescriptorUtil.methodDescriptor(method))) {
                    return MethodInfo.from(method);
                }
            }
            return null;
        });
    }

    /** Mode B: the user selected a method declaration. */
    private static PsiMethod fromSelection(PsiFile file, SelectionModel selection) {
        if (!selection.hasSelection()) {
            return null;
        }
        int from = selection.getSelectionStart();
        int to = selection.getSelectionEnd() - 1;
        if (to < from) {
            to = from;
        }
        PsiElement startElement = file.findElementAt(from);
        PsiElement endElement = file.findElementAt(to);
        PsiMethod startMethod = PsiTreeUtil.getParentOfType(startElement, PsiMethod.class);
        PsiMethod endMethod = PsiTreeUtil.getParentOfType(endElement, PsiMethod.class);
        if (startMethod == null || startMethod != endMethod) {
            return null;
        }

        // Prefer a selection that actually covers the method name identifier.
        PsiIdentifier name = startMethod.getNameIdentifier();
        if (name != null) {
            int nameStart = name.getTextRange().getStartOffset();
            int nameEnd = name.getTextRange().getEndOffset();
            if (from <= nameStart && selection.getSelectionEnd() >= nameEnd) {
                return startMethod;
            }
        }
        // Fallback: selection lies inside the method body/declaration.
        return startMethod;
    }

    /** Mode A: the caret is inside the method. */
    private static PsiMethod fromCaret(PsiFile file, CaretModel caret) {
        int offset = caret.getOffset();
        PsiMethod method = methodAt(file, offset);
        if (method == null) {
            method = methodAt(file, Math.max(0, offset - 1));
        }
        if (method == null) {
            method = methodOnLine(file, offset);
        }
        return method;
    }

    private static PsiMethod methodAt(PsiFile file, int offset) {
        PsiElement element = file.findElementAt(Math.max(0, offset));
        return element == null ? null : PsiTreeUtil.getParentOfType(element, PsiMethod.class);
    }

    /**
     * The caret is often placed in the leading indentation of a line, i.e. in whitespace that
     * belongs to the class body — fall back to the first real token on that line.
     */
    private static PsiMethod methodOnLine(PsiFile file, int offset) {
        com.intellij.openapi.editor.Document document =
                PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null || document.getTextLength() == 0) {
            return null;
        }
        int position = Math.min(offset, document.getTextLength());
        int line = document.getLineNumber(position);
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        for (int i = start; i < end; i++) {
            PsiElement element = file.findElementAt(i);
            if (element == null || element instanceof com.intellij.psi.PsiWhiteSpace) {
                continue;
            }
            return PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        }
        return null;
    }
}
