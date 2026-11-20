package com.debugmock.debug;

import com.debugmock.model.MockRule;
import com.debugmock.psi.JvmDescriptorUtil;
import com.debugmock.psi.MethodInfo;
import com.debugmock.store.MockRuleStore;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.ui.breakpoints.BreakpointManager;
import com.intellij.debugger.ui.breakpoints.MethodBreakpoint;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.List;

/**
 * Registers method-entry breakpoints for mock rules.
 *
 * <p>Called in two situations: when a rule is created while a debug session is already running,
 * and (importantly) when any debug session attaches later — local or remote — so rules created
 * before the session are applied on "the next Debug start" as required.</p>
 */
public final class MockBreakpointRegistrar {

    private static final Logger LOG = Logger.getInstance(MockBreakpointRegistrar.class);

    private MockBreakpointRegistrar() {
    }

    /** Register the breakpoint for a single rule at the moment it is created. */
    public static void registerIfDebugging(Project project, MethodInfo info, MockRule rule) {
        if (!rule.isEnabled() || !hasSession(project)) {
            return;
        }
        // Called from the dialog's save flow on the EDT; locating the breakpoint offset walks PSI.
        com.intellij.openapi.application.ReadAction.run(() -> addBreakpoint(project, info.getMethod()));
    }

    /**
     * Register breakpoints for ALL enabled rules. Invoked whenever a debug session attaches,
     * including Remote JVM Debug sessions. This covers the "no session yet -> apply on next
     * Debug start" case.
     */
    public static void registerAll(Project project) {
        if (!hasSession(project)) {
            return;
        }
        List<MockRule> rules = MockRuleStore.getInstance().load(project);
        if (rules.isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() ->
                ApplicationManager.getApplication().runReadAction(() -> {
                    for (MockRule rule : rules) {
                        if (rule.isEnabled()) {
                            registerForRule(project, rule);
                        }
                    }
                }));
    }

    /** Locate the exact method by class + method name + JVM signature and breakpoint it. */
    private static void registerForRule(Project project, MockRule rule) {
        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(rule.getClassName(), GlobalSearchScope.allScope(project));
        if (psiClass == null) {
            return;
        }
        for (PsiMethod method : psiClass.findMethodsByName(rule.getMethodName(), false)) {
            if (method.isConstructor()) {
                continue;
            }
            if (!rule.getSignature().equals(JvmDescriptorUtil.methodDescriptor(method))) {
                continue;
            }
            addBreakpoint(project, method);
        }
    }

    private static boolean hasSession(Project project) {
        DebuggerManagerEx manager = DebuggerManagerEx.getInstanceEx(project);
        return manager != null && !manager.getSessions().isEmpty();
    }

    private static void addBreakpoint(Project project, PsiMethod method) {
        PsiFile file = method.getContainingFile();
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        PsiIdentifier name = method.getNameIdentifier();
        if (document == null || name == null) {
            return;
        }
        int offset = name.getTextRange().getStartOffset();
        BreakpointManager breakpointManager = DebuggerManagerEx.getInstanceEx(project).getBreakpointManager();
        if (breakpointManager.findBreakpoint(document, offset, MethodBreakpoint.CATEGORY) != null) {
            return;
        }
        try {
            breakpointManager.addMethodBreakpoint(document, offset);
        } catch (RuntimeException e) {
            LOG.warn("Cannot register method breakpoint for " + method.getName(), e);
        }
    }
}
