package com.debugmock.toolwindow;

import com.debugmock.actions.CopyAgentOptionsAction;
import com.debugmock.model.MockAction;
import com.debugmock.model.MockRule;
import com.debugmock.psi.MethodInfo;
import com.debugmock.psi.MethodResolver;
import com.debugmock.store.MockRuleListener;
import com.debugmock.store.MockRuleStore;
import com.debugmock.ui.AddMockRuleDialog;
import com.debugmock.util.NotificationUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DebugMock tool window: a two-level tree — methods on the first level, their conditional rules
 * on the second. Rule actions (go to source, edit, enable/disable, delete) live on a toolbar above
 * the list and operate on the selected rule.
 */
public class MockToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        RulesPanel panel = new RulesPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "Rules", false);
        content.setIcon(IconLoader.getIcon("/icons/debugmock.svg", MockToolWindowFactory.class));
        toolWindow.getContentManager().addContent(content);
    }

    private static final class MethodNode {
        final String className;
        final String methodName;
        final String signature;

        MethodNode(String className, String methodName, String signature) {
            this.className = className;
            this.methodName = methodName;
            this.signature = signature;
        }

        @Override
        public String toString() {
            return className + "#" + methodName + signature;
        }
    }

    private static final class RulesPanel extends JPanel implements Disposable {

        private final Project project;
        private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Mock Rules");
        private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
        private final Tree tree = new Tree(treeModel);
        private final ActionToolbar toolbar;
        private final JCheckBox allRulesBox;
        private final MessageBusConnection messageBusConnection;
        /** Guards against an older background refresh overwriting a newer tree. */
        private final java.util.concurrent.atomic.AtomicLong refreshGeneration =
                new java.util.concurrent.atomic.AtomicLong();

        private RulesPanel(Project project) {
            super(new BorderLayout());
            this.project = project;
            tree.setCellRenderer(new RuleRenderer());
            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.getSelectionModel().setSelectionMode(
                    javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleMouse(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    handleMouse(e);
                }
            });
            tree.addTreeSelectionListener(e -> updateActions());

            DefaultActionGroup actions = new DefaultActionGroup();
            actions.add(new EditAction());
            actions.add(new NavigateAction());
            actions.add(new Separator());
            actions.add(new ToggleEnabledAction());
            actions.add(new DeleteAction());
            toolbar = ActionManager.getInstance()
                    .createActionToolbar("DebugMockRules", actions, true);
            toolbar.setTargetComponent(tree);

            // The master switch is a labelled checkbox on the far right, so it can never be
            // confused with the per-rule Enable/Disable icon on the left.
            allRulesBox = new JCheckBox("All rules");
            allRulesBox.setOpaque(false);
            allRulesBox.setToolTipText(
                    "Enable or disable every mock rule of the project (master switch)");
            allRulesBox.addActionListener(e ->
                    MockRuleStore.getInstance().setAllEnabled(project, allRulesBox.isSelected()));

            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(toolbar.getComponent(), BorderLayout.CENTER);
            JPanel masterHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
            masterHolder.setOpaque(false);
            masterHolder.add(allRulesBox);
            top.add(masterHolder, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            JBScrollPane scrollPane = new JBScrollPane(tree);
            scrollPane.setBorder(JBUI.Borders.empty());
            add(scrollPane, BorderLayout.CENTER);

            refresh();
            messageBusConnection = project.getMessageBus().connect(this);
            messageBusConnection.subscribe(MockRuleListener.TOPIC, () ->
                    SwingUtilities.invokeLater(this::refresh));
        }

        private void updateActions() {
            toolbar.updateActionsImmediately();
        }

        private MockRule selectedRule() {
            TreePath path = tree.getSelectionPath();
            if (path == null) {
                return null;
            }
            Object userObject = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            return userObject instanceof MockRule ? (MockRule) userObject : null;
        }

        private MethodNode selectedMethod() {
            TreePath path = tree.getSelectionPath();
            return path == null ? null : methodGroup(path);
        }

        private void handleMouse(MouseEvent e) {
            TreePath path = tree.getPathForLocation(e.getX(), e.getY());
            if (path == null) {
                // Clicking empty space must clear the selection; otherwise one row stays
                // highlighted forever and the whole list looks permanently "selected".
                tree.clearSelection();
                return;
            }
            tree.setSelectionPath(path);
            Object selected = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (SwingUtilities.isRightMouseButton(e)) {
                JPopupMenu menu = selected instanceof MockRule
                        ? ruleMenu((MockRule) selected) : methodMenu(path);
                menu.show(e.getComponent(), e.getX(), e.getY());
            } else if (selected instanceof MockRule && e.getClickCount() == 2) {
                // A single click only selects; navigating happens through "Go to Source" so a
                // click never moves the editor away from what the user is looking at.
                editRule((MockRule) selected);
            }
        }

        /** Opens the rule's method/line in the editor. */
        private void navigateTo(MockRule rule) {
            MethodInfo info = MethodResolver.resolveByRule(
                    project, rule.getClassName(), rule.getMethodName(), rule.getSignature());
            if (info == null) {
                NotificationUtil.warn(project, "DebugMock",
                        "Method no longer exists: " + rule.methodKey());
                return;
            }
            VirtualFile[] fileHolder = new VirtualFile[1];
            int offset = ReadAction.compute(() -> {
                PsiMethod method = info.getMethod();
                PsiFile file = method.getContainingFile();
                VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
                if (virtualFile == null) {
                    return -1;
                }
                fileHolder[0] = virtualFile;
                int target = method.getTextOffset();
                if (rule.getLineNumber() > 0) {
                    // Follow the anchored statement instead of a possibly stale stored line.
                    int resolved = com.debugmock.psi.RuleLines.resolve(rule, method);
                    Document document = PsiDocumentManager.getInstance(project).getDocument(file);
                    if (document != null && document.getLineCount() > 0) {
                        int line = Math.max(0,
                                Math.min(resolved - 1, document.getLineCount() - 1));
                        target = document.getLineStartOffset(line);
                    }
                }
                return target;
            });
            if (offset < 0 || fileHolder[0] == null) {
                NotificationUtil.warn(project, "DebugMock",
                        "Cannot open the source file of " + rule.methodKey());
                return;
            }
            new OpenFileDescriptor(project, fileHolder[0], offset).navigate(true);
        }

        /** Minimal rule menu; the main actions are on the toolbar. */
        private JPopupMenu ruleMenu(MockRule rule) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem goTo = new JMenuItem("Go to Source");
            goTo.addActionListener(a -> navigateTo(rule));
            JMenuItem edit = new JMenuItem("Edit...");
            edit.addActionListener(a -> editRule(rule));
            menu.add(goTo);
            menu.add(edit);
            return menu;
        }

        private JPopupMenu methodMenu(TreePath path) {
            MethodNode group = methodGroup(path);
            JPopupMenu menu = new JPopupMenu();
            JMenuItem add = new JMenuItem("Add Rule...");
            add.addActionListener(a -> {
                MethodInfo info = MethodResolver.resolveByRule(
                        project, group.className, group.methodName, group.signature);
                if (info != null) {
                    MockRule created = AddMockRuleDialog.show(project, info, null);
                    if (created != null) {
                        MockRuleStore.getInstance().upsert(project, created);
                    }
                }
            });
            JMenuItem disable = new JMenuItem("Disable All");
            disable.addActionListener(a -> MockRuleStore.getInstance().setEnabledForMethod(
                    project, group.className, group.methodName, group.signature, false));
            JMenuItem enable = new JMenuItem("Enable All");
            enable.addActionListener(a -> MockRuleStore.getInstance().setEnabledForMethod(
                    project, group.className, group.methodName, group.signature, true));
            JMenuItem delete = new JMenuItem("Delete All");
            delete.addActionListener(a -> MockRuleStore.getInstance().removeAllForMethod(
                    project, group.className, group.methodName, group.signature));
            menu.add(add);
            menu.add(enable);
            menu.add(disable);
            menu.addSeparator();
            menu.add(delete);
            return menu;
        }

        private MethodNode methodGroup(TreePath path) {
            for (int i = path.getPathCount() - 1; i >= 0; i--) {
                Object userObject = ((DefaultMutableTreeNode) path.getPathComponent(i)).getUserObject();
                if (userObject instanceof MethodNode) {
                    return (MethodNode) userObject;
                }
            }
            return null;
        }

        private void editRule(MockRule rule) {
            MethodInfo info = MethodResolver.resolveByRule(
                    project, rule.getClassName(), rule.getMethodName(), rule.getSignature());
            if (info == null) {
                NotificationUtil.warn(project, "DebugMock", "Method no longer exists: " + rule.methodKey());
                return;
            }
            MockRule updated = AddMockRuleDialog.show(project, info, rule);
            if (updated != null) {
                MockRuleStore.getInstance().upsert(project, updated);
            }
        }

        private void refresh() {
            if (project.isDisposed()) {
                return;
            }
            final String selectedId = selectedRule() == null ? null : selectedRule().getId();
            final long generation = refreshGeneration.incrementAndGet();
            // File I/O and PSI lookups must not run on the EDT: resolving a rule's class can
            // trigger indexing and freeze the UI for seconds on a cold project.
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                if (project.isDisposed()) {
                    return;
                }
                List<MockRule> rules = MockRuleStore.getInstance().load(project);
                boolean dumb = com.intellij.openapi.project.DumbService.getInstance(project).isDumb();
                if (!dumb) {
                    resolveDisplayLines(rules);
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed() || generation != refreshGeneration.get()) {
                        return;
                    }
                    applyRules(rules, selectedId);
                });
                if (dumb) {
                    // Show the stored lines right away, then refresh again once indexing is done.
                    com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart(() ->
                            ApplicationManager.getApplication().invokeLater(this::refresh));
                }
            });
        }

        /** Rebuilds the tree from already-resolved copies; always runs on the EDT. */
        private void applyRules(List<MockRule> rules, String selectedId) {
            root.removeAllChildren();
            Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
            for (MockRule rule : rules) {
                DefaultMutableTreeNode group = groups.get(rule.methodKey());
                if (group == null) {
                    group = new DefaultMutableTreeNode(
                            new MethodNode(rule.getClassName(), rule.getMethodName(), rule.getSignature()));
                    groups.put(rule.methodKey(), group);
                    root.add(group);
                }
                group.add(new DefaultMutableTreeNode(rule));
            }
            treeModel.reload();
            for (int i = 0; i < tree.getRowCount(); i++) {
                tree.expandRow(i);
            }
            // Keep the selection stable across reloads (e.g. after enable/disable).
            if (selectedId != null) {
                selectById(selectedId);
            }
            updateActions();
            updateMasterSwitch(rules);
        }

        /**
         * Points the list labels at the line the anchored statement currently lives on. Only the
         * in-memory copies are touched here; {@code MockRuleLineSync} persists the correction.
         * Runs on a pooled thread (never the EDT).
         */
        private void resolveDisplayLines(List<MockRule> rules) {
            try {
                ReadAction.compute(() -> {
                    for (MockRule rule : rules) {
                        if (rule.getLineNumber() <= 0) {
                            continue;
                        }
                        MethodInfo info = MethodResolver.resolveByRule(
                                project, rule.getClassName(), rule.getMethodName(), rule.getSignature());
                        if (info == null) {
                            continue;
                        }
                        int resolved = com.debugmock.psi.RuleLines.resolve(rule, info.getMethod());
                        if (resolved > 0) {
                            rule.setLineNumber(resolved);
                        }
                    }
                    return null;
                });
            } catch (com.intellij.openapi.project.IndexNotReadyException ignored) {
                // Indexing started between the check and the lookup; the next refresh retries.
            }
        }

        /** Master switch: checked only when every rule is enabled (mixed state stays unchecked). */
        private void updateMasterSwitch(List<MockRule> rules) {
            boolean all = !rules.isEmpty();
            boolean any = false;
            for (MockRule rule : rules) {
                if (rule.isEnabled()) {
                    any = true;
                } else {
                    all = false;
                }
            }
            allRulesBox.setSelected(all);
            allRulesBox.setEnabled(!rules.isEmpty());
            allRulesBox.setText(all ? "All rules: on" : (rules.isEmpty()
                    ? "All rules" : (any ? "All rules: mixed" : "All rules: off")));
        }

        private void selectById(String id) {
            for (int row = 0; row < tree.getRowCount(); row++) {
                TreePath path = tree.getPathForRow(row);
                if (path == null) {
                    continue;
                }
                Object userObject = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (userObject instanceof MockRule && id.equals(((MockRule) userObject).getId())) {
                    tree.setSelectionPath(path);
                    return;
                }
            }
        }

        // --- toolbar actions -------------------------------------------------------------------

        private final class EditAction extends AnAction {

            @Override
            @NotNull
            public com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                // update() reads the Swing tree selection, which must happen on the UI thread.
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
            EditAction() {
                super("Edit", "Edit the selected rule", AllIcons.Actions.Edit);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                MockRule rule = selectedRule();
                if (rule != null) {
                    editRule(rule);
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(selectedRule() != null);
            }
        }

        private final class NavigateAction extends AnAction {

            @Override
            @NotNull
            public com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                // update() reads the Swing tree selection, which must happen on the UI thread.
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
            NavigateAction() {
                super("Go to Source", "Jump to the rule's source line", AllIcons.Actions.EditSource);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                MockRule rule = selectedRule();
                if (rule != null) {
                    navigateTo(rule);
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(selectedRule() != null);
            }
        }

        private final class ToggleEnabledAction extends AnAction {

            @Override
            @NotNull
            public com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                // update() reads the Swing tree selection, which must happen on the UI thread.
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
            ToggleEnabledAction() {
                super("Enable / Disable", "Toggle the selected rule (or every rule of a method)",
                        AllIcons.Actions.Suspend);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                MockRule rule = selectedRule();
                if (rule != null) {
                    MockRuleStore.getInstance().toggle(project, rule);
                    return;
                }
                MethodNode method = selectedMethod();
                if (method != null) {
                    boolean anyEnabled = false;
                    for (MockRule candidate : MockRuleStore.getInstance().load(project)) {
                        if (candidate.matches(method.className, method.methodName, method.signature)
                                && candidate.isEnabled()) {
                            anyEnabled = true;
                            break;
                        }
                    }
                    MockRuleStore.getInstance().setEnabledForMethod(
                            project, method.className, method.methodName, method.signature, !anyEnabled);
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                MockRule rule = selectedRule();
                MethodNode method = selectedMethod();
                e.getPresentation().setEnabled(rule != null || method != null);
                boolean enabled = rule != null ? rule.isEnabled() : anyEnabled(method);
                e.getPresentation().setText(enabled ? "Disable" : "Enable");
                e.getPresentation().setIcon(enabled
                        ? AllIcons.Actions.Suspend : AllIcons.Actions.Resume);
            }
        }

        private final class DeleteAction extends AnAction {

            @Override
            @NotNull
            public com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                // update() reads the Swing tree selection, which must happen on the UI thread.
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
            DeleteAction() {
                super("Delete", "Delete the selected rule (or every rule of a method)",
                        AllIcons.Actions.GC);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                MockRule rule = selectedRule();
                if (rule != null) {
                    MockRuleStore.getInstance().remove(project, rule);
                    return;
                }
                MethodNode method = selectedMethod();
                if (method != null) {
                    MockRuleStore.getInstance().removeAllForMethod(
                            project, method.className, method.methodName, method.signature);
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(selectedRule() != null || selectedMethod() != null);
            }
        }

        /** True when at least one rule of the method is enabled (used for method-row actions). */
        private boolean anyEnabled(MethodNode method) {
            if (method == null) {
                return false;
            }
            for (MockRule candidate : MockRuleStore.getInstance().load(project)) {
                if (candidate.matches(method.className, method.methodName, method.signature)
                        && candidate.isEnabled()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void dispose() {
            messageBusConnection.dispose();
        }
    }

    /**
     * Standard platform renderer: selection, focus and background colours come from the IDE theme
     * (Classic and New UI, light and dark), so no LAF workarounds are needed.
     */
    private static final class RuleRenderer extends ColoredTreeCellRenderer {

        /** Small round state markers: green when the rule runs, grey when it is disabled. */
        private static final Icon ENABLED_ICON = dot(new Color(0x36B37E));
        private static final Icon DISABLED_ICON = dot(new Color(0x9AA0A6));

        private static Icon dot(Color color) {
            java.awt.image.BufferedImage image =
                    new java.awt.image.BufferedImage(12, 12, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fillOval(3, 3, 6, 6);
            g.dispose();
            return new javax.swing.ImageIcon(image);
        }

        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected,
                                          boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Object userObject = value instanceof DefaultMutableTreeNode
                    ? ((DefaultMutableTreeNode) value).getUserObject() : null;
            if (userObject instanceof MockRule) {
                MockRule rule = (MockRule) userObject;
                String condition = rule.hasCondition() ? abbreviate(rule.getCondition()) : "always";
                String detail = detailOf(rule);
                String line = rule.getLineNumber() > 0 ? "line " + rule.getLineNumber() : "entry";
                // State is shown by the dot only: the text keeps the normal colour.
                setIcon(rule.isEnabled() ? ENABLED_ICON : DISABLED_ICON);
                append(rule.getAction() + "  " + line + "  ·  " + condition + "  →  " + detail,
                        selected ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                                : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            } else if (userObject instanceof MethodNode) {
                MethodNode node = (MethodNode) userObject;
                int dot = node.className.lastIndexOf('.');
                String simpleClass = dot < 0 ? node.className : node.className.substring(dot + 1);
                setIcon(AllIcons.Nodes.Method);
                append(simpleClass + "." + node.methodName + "()", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            }
        }

        private static String detailOf(MockRule rule) {
            String statement = rule.getStatement();
            if (statement == null || statement.trim().isEmpty()) {
                // RETURN rules backed by raw JSON keep their payload in returnValue.
                statement = rule.getReturnValue();
            }
            if (statement != null && !statement.trim().isEmpty()) {
                String oneLine = abbreviate(statement);
                if (rule.getAction() == MockAction.RETURN && oneLine.startsWith("return ")) {
                    oneLine = oneLine.substring("return ".length());
                }
                return oneLine;
            }
            if (rule.getAction() == MockAction.SKIP) {
                return "skip original body";
            }
            return "";
        }

        private static String abbreviate(String text) {
            if (text == null) {
                return "";
            }
            String oneLine = text.replaceAll("\\s+", " ").trim();
            return oneLine.length() > 48 ? oneLine.substring(0, 48) + "..." : oneLine;
        }
    }
}
