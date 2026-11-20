package com.debugmock.ui;

import com.debugmock.debug.MockBreakpointRegistrar;
import com.debugmock.model.MockRule;
import com.debugmock.psi.MethodInfo;
import com.debugmock.store.MockRuleStore;
import com.debugmock.util.NotificationUtil;
import com.intellij.openapi.project.Project;

/**
 * Shared flow used by both editor actions and the gutter icon: open the dialog, persist the rule,
 * register a breakpoint when debugging and notify the user.
 */
public final class RuleUiService {

    private RuleUiService() {
    }

    public static void createOrEdit(Project project, MethodInfo info, MockRule existing) {
        boolean isNew = existing == null;
        MockRule rule = AddMockRuleDialog.show(project, info, existing);
        if (rule == null) {
            return;
        }
        MockRuleStore.getInstance().upsert(project, rule);
        MockBreakpointRegistrar.registerIfDebugging(project, info, rule);
        NotificationUtil.info(project, "DebugMock",
                (isNew ? "Created" : "Updated") + " mock rule for " + info.getClassName()
                        + "#" + info.getMethodName());
    }
}
