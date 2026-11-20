package com.debugmock.gutter;

import com.debugmock.model.MockRule;
import com.debugmock.psi.MethodInfo;
import com.debugmock.store.MockRuleStore;
import com.debugmock.ui.AddMockRuleDialog;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Gutter icon for lines that already carry a rule.
 *
 * <p>Icons are intentionally <em>not</em> shown on every statement: that floods the gutter. A line
 * rule is added from the editor's context menu ({@code Add DebugMock Rule at This Line}); once a
 * rule exists, its line gets a small icon whose click opens the rule actions (edit / enable /
 * delete / add another rule at the same line).</p>
 */
public class MockStatementLineMarkerProvider implements LineMarkerProvider {

    private static final Icon LINE_ICON = loadIcon("/icons/mock-line.svg");

    private static Icon loadIcon(String path) {
        Icon icon = IconLoader.getIcon(path, MockStatementLineMarkerProvider.class);
        return icon != null ? icon : AllIcons.Actions.Execute;
    }

    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // Everything is done once per file in collectSlowLineMarkers (one rule-file read).
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
                                       @NotNull Collection<? super LineMarkerInfo<?>> result) {
        if (elements.isEmpty()) {
            return;
        }
        PsiFile file = elements.get(0).getContainingFile();
        if (!(file instanceof PsiJavaFile)) {
            return;
        }
        Project project = file.getProject();
        if (project.isDisposed()) {
            return;
        }
        List<MockRule> rules = MockRuleStore.getInstance().load(project);
        if (rules.isEmpty()) {
            return;
        }
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            return;
        }

        // Rules are stored as line numbers; resolve them to the statement they are anchored to.
        // One resolution per method is enough, so the map is built lazily per method key.
        Map<String, Map<String, Integer>> resolvedLines = new HashMap<>();

        for (PsiElement element : elements) {
            PsiStatement statement = PsiTreeUtil.getParentOfType(element, PsiStatement.class);
            if (statement == null || statement instanceof PsiCodeBlock) {
                continue;
            }
            // Only the very first leaf of the statement, so one icon per statement.
            if (PsiTreeUtil.getDeepestFirst(statement) != element) {
                continue;
            }

            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (method == null || method.isConstructor() || method.getBody() == null) {
                continue;
            }
            PsiCodeBlock body = method.getBody();
            if (!PsiTreeUtil.isAncestor(body, statement, false)) {
                continue;
            }

            int offset = element.getTextRange().getStartOffset();
            int line = document.getLineNumber(offset);
            if (line == document.getLineNumber(method.getTextRange().getStartOffset())) {
                continue;
            }
            // Only the first statement on a line gets the icon.
            int lineStart = document.getLineStartOffset(line);
            String prefix = document.getCharsSequence().subSequence(lineStart, offset).toString();
            if (!prefix.trim().isEmpty()) {
                continue;
            }

            MethodInfo info;
            try {
                info = MethodInfo.from(method);
            } catch (RuntimeException e) {
                continue;
            }
            int targetLine = line + 1;
            Map<String, Integer> lines = resolvedLines.computeIfAbsent(
                    info.getClassName() + "#" + info.getMethodName() + info.getSignature(),
                    key -> resolveLines(rules, info));
            List<MockRule> atLine = rulesAt(rules, info, targetLine, lines);
            if (atLine.isEmpty()) {
                continue;
            }

            result.add(new LineMarkerInfo<>(
                    element,
                    TextRange.from(offset, 0),
                    LINE_ICON,
                    e -> "DebugMock: " + atLine.size() + " rule(s) at line " + targetLine,
                    (mouseEvent, psiElement) -> showRules(mouseEvent, project, info, targetLine, atLine),
                    Alignment.RIGHT,
                    (Supplier<String>) () -> "DebugMock line rule"
            ));
        }
    }

    /** Resolves every rule of the given method to the line its anchored statement lives on. */
    private static Map<String, Integer> resolveLines(List<MockRule> rules, MethodInfo info) {
        Map<String, Integer> result = new HashMap<>();
        for (MockRule rule : rules) {
            if (rule.getLineNumber() > 0 && rule.matches(
                    info.getClassName(), info.getMethodName(), info.getSignature())) {
                result.put(rule.getId(),
                        com.debugmock.psi.RuleLines.resolve(rule, info.getMethod()));
            }
        }
        return result;
    }

    private static List<MockRule> rulesAt(List<MockRule> rules, MethodInfo info, int line,
                                          Map<String, Integer> resolvedLines) {
        List<MockRule> result = new ArrayList<>();
        for (MockRule rule : rules) {
            Integer resolved = resolvedLines.get(rule.getId());
            if (resolved != null && resolved == line && rule.matches(
                    info.getClassName(), info.getMethodName(), info.getSignature())) {
                result.add(rule);
            }
        }
        return result;
    }

    private static void showRules(MouseEvent event, Project project, MethodInfo info, int line,
                                  List<MockRule> rules) {
        RulePopupMenus.show(project, info, rules, event.getComponent(), event.getX(), event.getY(),
                "Add Rule at Line " + line + "...", () -> {
                    MockRule rule = AddMockRuleDialog.showExecute(project, info, line);
                    if (rule != null) {
                        MockRuleStore.getInstance().upsert(project, rule);
                    }
                });
    }
}
