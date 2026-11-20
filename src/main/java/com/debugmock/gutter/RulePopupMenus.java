package com.debugmock.gutter;

import com.debugmock.model.MockAction;
import com.debugmock.model.MockRule;
import com.debugmock.psi.MethodInfo;
import com.debugmock.store.MockRuleStore;
import com.debugmock.ui.AddMockRuleDialog;
import com.intellij.openapi.project.Project;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.util.List;

/**
 * Gutter popup: the rules that live at this method / line and the entry point for a new one.
 *
 * <p>It only lists rules (clicking one opens it for editing) plus "Add Rule…". Deleting and
 * enabling / disabling a rule are done in the DebugMock tool window, so the gutter stays a quick
 * navigator instead of a second management UI.</p>
 */
final class RulePopupMenus {

    private RulePopupMenus() {
    }

    static void show(Project project, MethodInfo info, List<MockRule> rules,
                     Component component, int x, int y, String addLabel, Runnable addAction) {
        JPopupMenu menu = new JPopupMenu("DebugMock");
        for (MockRule rule : rules) {
            JMenuItem item = new JMenuItem(label(rule, info));
            item.addActionListener(a -> editRule(project, info, rule));
            menu.add(item);
        }
        if (!rules.isEmpty()) {
            menu.addSeparator();
        }
        JMenuItem add = new JMenuItem(addLabel);
        add.addActionListener(a -> addAction.run());
        menu.add(add);
        menu.show(component, x, y);
    }

    private static String label(MockRule rule, MethodInfo info) {
        String action = rule.getAction() == MockAction.RETURN ? "RETURN"
                : rule.getAction() == MockAction.THROW ? "THROW"
                : rule.getAction() == MockAction.EXECUTE ? "EXECUTE" : "SKIP";
        // Show the line the anchored statement currently lives on, not a stale stored number.
        int line = rule.getLineNumber() > 0
                ? com.intellij.openapi.application.ReadAction.compute(
                        () -> com.debugmock.psi.RuleLines.resolve(rule, info.getMethod()))
                : rule.getLineNumber();
        String where = line > 0 ? "@line " + line : "@entry";
        String condition = rule.hasCondition() ? rule.getCondition() : "always";
        return (rule.isEnabled() ? "[ON] " : "[OFF] ") + action + "  " + where + "  " + condition;
    }

    private static void editRule(Project project, MethodInfo info, MockRule rule) {
        MockRule updated = AddMockRuleDialog.show(project, info, rule);
        if (updated != null) {
            MockRuleStore.getInstance().upsert(project, updated);
        }
    }
}
