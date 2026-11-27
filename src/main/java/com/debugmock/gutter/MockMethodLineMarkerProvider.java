package com.debugmock.gutter;

import com.debugmock.model.MockRule;
import com.debugmock.psi.MethodInfo;
import com.debugmock.psi.MethodResolver;
import com.debugmock.store.MockRuleStore;
import com.debugmock.ui.AddMockRuleDialog;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;

/**
 * Gutter icon next to Java method declarations that already have at least one mock rule.
 * Clicking lists the method's rules (edit / enable / disable / delete) and offers a shortcut to
 * add another one. Methods without rules get no icon, so the gutter stays clean; create a rule
 * from the editor context menu ({@code DebugMock → Add Mock Rule}) or the tool window.
 */
public class MockMethodLineMarkerProvider implements LineMarkerProvider {

    private static final Icon RULE_ICON = loadIcon("/icons/mock-rule.svg");

    private static Icon loadIcon(String path) {
        Icon icon = IconLoader.getIcon(path, MockMethodLineMarkerProvider.class);
        return icon != null ? icon : AllIcons.Actions.Execute;
    }

    @Override
    public @Nullable LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier) || !(element.getParent() instanceof PsiMethod)) {
            return null;
        }
        PsiMethod method = (PsiMethod) element.getParent();
        if (method.getNameIdentifier() != element || method.isConstructor()) {
            return null;
        }

        MethodInfo info;
        try {
            info = MethodInfo.from(method);
        } catch (RuntimeException e) {
            return null;
        }
        List<MockRule> rules = MockRuleStore.getInstance().findAll(
                element.getProject(), info.getClassName(), info.getMethodName(), info.getSignature());
        if (rules.isEmpty()) {
            // Methods without a rule stay clean: no gutter icon on every declaration.
            // A new rule is added from the editor context menu or the DebugMock tool window.
            return null;
        }

        return new LineMarkerInfo<>(
                element,
                TextRange.from(element.getTextRange().getStartOffset(), 0),
                RULE_ICON,
                e -> "DebugMock: " + rules.size() + " mock rule(s)",
                (mouseEvent, psiElement) -> showRules(mouseEvent, psiElement.getProject(), info),
                Alignment.RIGHT,
                (Supplier<String>) () -> "DebugMock gutter icon"
        );
    }

    private static void showRules(MouseEvent event, Project project, MethodInfo info) {
        List<MockRule> rules = MockRuleStore.getInstance().findAll(
                project, info.getClassName(), info.getMethodName(), info.getSignature());
        if (rules.isEmpty()) {
            openCreate(project, info);
            return;
        }
        RulePopupMenus.show(project, info, rules, event.getComponent(), event.getX(), event.getY(),
                "Add Mock Rule...", () -> openCreate(project, info));
    }

    private static void openCreate(Project project, MethodInfo info) {
        MockRule rule = AddMockRuleDialog.show(project, info, null);
        if (rule != null) {
            MockRuleStore.getInstance().upsert(project, rule);
        }
    }
}
