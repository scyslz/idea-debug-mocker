package com.debugmock.store;

import com.debugmock.model.MockRule;
import com.debugmock.psi.JvmDescriptorUtil;
import com.debugmock.psi.RuleLines;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiTreeChangeAdapter;
import com.intellij.psi.PsiTreeChangeEvent;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps stored line rules attached to their code while the source is edited.
 *
 * <p>Breakpoints move with their line because the IDE tracks them through PSI. Mock rules are
 * persisted as line numbers, so without this service deleting a line above a rule silently moves
 * the rule to a different statement. On every PSI change of a Java file this service relocates the
 * affected rules (see {@link RuleLines}) and writes the corrected line back to rules.json — so the
 * javaagent, which reads that file, always instruments the right statement.</p>
 */
@Service(Service.Level.PROJECT)
public final class MockRuleLineSync {

    /** A short debounce keeps typing from re-writing rules.json on every keystroke. */
    private static final int DEBOUNCE_MS = 400;

    private final Project project;
    private final Alarm alarm;
    private final Set<VirtualFile> pending = new HashSet<>();

    public MockRuleLineSync(@NotNull Project project) {
        this.project = project;
        this.alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, project);
        PsiManager.getInstance(project).addPsiTreeChangeListener(new PsiTreeChangeAdapter() {
            @Override
            public void childAdded(@NotNull PsiTreeChangeEvent event) {
                schedule(event);
            }

            @Override
            public void childRemoved(@NotNull PsiTreeChangeEvent event) {
                schedule(event);
            }

            @Override
            public void childReplaced(@NotNull PsiTreeChangeEvent event) {
                schedule(event);
            }

            @Override
            public void childMoved(@NotNull PsiTreeChangeEvent event) {
                schedule(event);
            }

            @Override
            public void propertyChanged(@NotNull PsiTreeChangeEvent event) {
                schedule(event);
            }
        }, project);
    }

    /** Called whenever a project reads its rules, so the listener is installed for that project. */
    public static void ensureInstalled(@NotNull Project project) {
        if (!project.isDisposed()) {
            project.getService(MockRuleLineSync.class);
        }
    }

    private void schedule(PsiTreeChangeEvent event) {
        PsiFile file = event.getFile();
        if (!(file instanceof PsiJavaFile)) {
            return;
        }
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        synchronized (pending) {
            pending.add(virtualFile);
        }
        alarm.cancelAllRequests();
        alarm.addRequest(this::syncPending, DEBOUNCE_MS);
    }

    private void syncPending() {
        Set<VirtualFile> files;
        synchronized (pending) {
            files = new HashSet<>(pending);
            pending.clear();
        }
        if (files.isEmpty() || project.isDisposed()) {
            return;
        }
        ApplicationManager.getApplication().runReadAction(() -> {
            if (project.isDisposed()) {
                return;
            }
            // Class lookup needs the Java index; retry the same files once indexing is finished.
            if (com.intellij.openapi.project.DumbService.getInstance(project).isDumb()) {
                synchronized (pending) {
                    pending.addAll(files);
                }
                com.intellij.openapi.project.DumbService.getInstance(project)
                        .runWhenSmart(this::syncPending);
                return;
            }
            try {
                syncRules(files);
            } catch (com.intellij.openapi.project.IndexNotReadyException ignored) {
                synchronized (pending) {
                    pending.addAll(files);
                }
            }
        });
    }

    private void syncRules(Set<VirtualFile> files) {
        List<MockRule> rules = MockRuleStore.getInstance().load(project);
        boolean changed = false;
        for (MockRule rule : rules) {
            PsiMethod method = findMethod(rule, files);
            if (method == null) {
                continue;
            }
            boolean ruleChanged = false;
            // Backfill the interface flag for rules created before it existed: interface
            // methods have no body, so the agent has to instrument the implementors.
            if (!rule.isInterfaceRule()) {
                com.intellij.psi.PsiClass owner = method.getContainingClass();
                if ((owner != null && owner.isInterface()) || method.getBody() == null) {
                    rule.setInterfaceRule(true);
                    ruleChanged = true;
                }
            }
            if (ruleChanged) {
                rule.setUpdatedAt(System.currentTimeMillis());
                changed = true;
            }
            if (rule.getLineNumber() <= 0) {
                continue;
            }
            int resolved = RuleLines.resolve(rule, method);
            if (resolved <= 0) {
                continue;
            }
            boolean lineMoved = resolved != rule.getLineNumber();
            boolean needsAnchor = !rule.hasLineAnchor() || rule.getLineOrdinal() < 0;
            if (!lineMoved && !needsAnchor) {
                continue;
            }
            String anchor = RuleLines.anchorFor(method, resolved);
            int ordinal = RuleLines.ordinalFor(method, resolved);
            if (anchor == null || ordinal < 0) {
                // Not a statement start (e.g. the anchor statement was deleted): leave it.
                continue;
            }
            rule.setLineNumber(resolved);
            rule.setLineAnchor(anchor);
            rule.setLineOrdinal(ordinal);
            rule.setUpdatedAt(System.currentTimeMillis());
            changed = true;
        }
        if (changed) {
            MockRuleStore.getInstance().save(project, rules);
        }
    }

    private PsiMethod findMethod(MockRule rule, Set<VirtualFile> files) {
        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(rule.getClassName(), GlobalSearchScope.allScope(project));
        if (psiClass == null) {
            return null;
        }
        PsiFile file = psiClass.getContainingFile();
        if (file == null || file.getVirtualFile() == null
                || !files.contains(file.getVirtualFile())) {
            return null;
        }
        for (PsiMethod method : psiClass.findMethodsByName(rule.getMethodName(), false)) {
            if (!method.isConstructor() && rule.getSignature().equals(JvmDescriptorUtil.methodDescriptor(method))) {
                return method;
            }
        }
        return null;
    }
}
