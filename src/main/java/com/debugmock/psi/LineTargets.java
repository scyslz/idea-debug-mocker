package com.debugmock.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;

/**
 * Maps a caret / gutter line to the source line where a rule can actually execute.
 *
 * <p>Instrumentation hooks a rule onto the line-number entry of a <em>statement start</em>, so a
 * line in the middle of an expression, a blank line or a closing brace cannot host a rule. Those
 * positions are normalised to the enclosing statement's first line, or to the next statement.</p>
 */
public final class LineTargets {

    private LineTargets() {
    }

    /** 1-based statement line for the caret, or 0 when the method body has no statement. */
    public static int statementLineAtCaret(Project project, Editor editor, MethodInfo info) {
        // Called from an AnAction on the EDT; the PSI traversal below needs read access.
        return com.intellij.openapi.application.ReadAction.compute(
                () -> statementLineAtCaretUnderReadAction(project, editor, info));
    }

    private static int statementLineAtCaretUnderReadAction(Project project, Editor editor,
                                                           MethodInfo info) {
        Document document = editor.getDocument();
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (file == null) {
            return info.getLineNumber();
        }
        int caretLine = document.getLineNumber(
                Math.min(editor.getCaretModel().getOffset(), document.getTextLength()));
        return normalize(file, document, info.getMethod(), caretLine + 1);
    }

    /**
     * Returns the 1-based line of the statement that should be used for a rule requested at
     * {@code oneBasedLine}. 0 means no statement is available.
     */
    public static int normalize(PsiFile file, Document document, PsiMethod method, int oneBasedLine) {
        if (method == null || method.getBody() == null) {
            return 0;
        }
        int requested = Math.max(0, Math.min(oneBasedLine - 1, document.getLineCount() - 1));

        PsiElement firstOnLine = firstNonWhitespace(file, document, requested);
        PsiStatement enclosing = firstOnLine == null ? null
                : PsiTreeUtil.getParentOfType(firstOnLine, PsiStatement.class);
        if (enclosing != null && PsiTreeUtil.isAncestor(method.getBody(), enclosing, false)) {
            return document.getLineNumber(enclosing.getTextRange().getStartOffset()) + 1;
        }

        // Blank line / comment / brace: use the next statement that starts at or after the line.
        int best = Integer.MAX_VALUE;
        for (PsiStatement statement : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiStatement.class)) {
            if (statement instanceof com.intellij.psi.PsiCodeBlock) {
                continue;
            }
            int line = document.getLineNumber(statement.getTextRange().getStartOffset());
            if (line >= requested) {
                best = Math.min(best, line);
            }
        }
        return best == Integer.MAX_VALUE ? 0 : best + 1;
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
}
