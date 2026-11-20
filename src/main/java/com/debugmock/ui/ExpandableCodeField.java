package com.debugmock.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;

/**
 * A Java code editor that is single-line by default and expands to a multi-line text area.
 *
 * <p>{@code EditorTextField.setOneLineMode} only flips a flag that is read when the editor is
 * created, so toggling it on a live field has no visible effect. The field is therefore recreated
 * around the same document whenever the state changes.</p>
 *
 * <p>The expanded field is hosted in a scroll pane: the embedded IntelliJ editor does not bring its
 * own scrollbars, so without one a long block would either be clipped or stretch the dialog.</p>
 *
 * <p>The field expands on its own as soon as the content needs more than one line (paste, JSON
 * example, a rule that already had multi-line code). Enter is never allowed to submit the dialog:
 * it is consumed while the caret is in the editor, so it can only ever reach the editor itself.</p>
 */
public final class ExpandableCodeField extends JPanel {

    /** Shared width for every code / text field in the dialog. */
    public static final int FIELD_WIDTH = 460;
    private static final int COLLAPSED_HEIGHT = 30;
    private static final int EXPANDED_HEIGHT = 160;

    private final Project project;
    private final StatementEditorUtil.CodeDocument code;
    private final JButton toggleButton;

    private EditorTextField field;
    /** What is actually shown in the middle: the field itself, or a scroll pane around it. */
    private JComponent host;
    private boolean expanded;
    private String placeholder = "";
    private KeyEventDispatcher enterDispatcher;
    private boolean dispatcherInstalled;
    private Editor caretEditor;

    public ExpandableCodeField(@NotNull Project project,
                               @NotNull StatementEditorUtil.CodeDocument code) {
        super(new BorderLayout(4, 0));
        this.project = project;
        this.code = code;
        // Open already expanded when the rule being edited holds a multi-line block.
        this.expanded = code.getDocument().getText().indexOf('\n') >= 0;

        toggleButton = new JButton(expanded ? AllIcons.General.ArrowUp : AllIcons.General.ArrowDown);
        toggleButton.setToolTipText("Expand / collapse editor");
        toggleButton.setPreferredSize(new Dimension(28, COLLAPSED_HEIGHT));
        toggleButton.addActionListener(e -> toggle());

        // The button keeps its own height when the editor grows: it is anchored to the top.
        JPanel toggleHolder = new JPanel(new BorderLayout());
        toggleHolder.setOpaque(false);
        toggleHolder.add(toggleButton, BorderLayout.NORTH);

        rebuild();
        add(toggleHolder, BorderLayout.EAST);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
    }

    /** Creates the field (and the scroll pane in expanded mode) for the current state. */
    private void rebuild() {
        Editor oldEditor = field == null ? null : field.getEditor();
        int caret = oldEditor == null ? -1 : oldEditor.getCaretModel().getOffset();
        if (host != null) {
            remove(host);
        }
        if (oldEditor != null) {
            EditorFactory.getInstance().releaseEditor(oldEditor);
        }

        EditorTextField created = code.createField(!expanded, placeholder);
        created.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        created.setPlaceholder(placeholder);
        created.setShowPlaceholderWhenFocused(false);
        created.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                // Typing / pasting a line break shows the whole block automatically.
                if (!ExpandableCodeField.this.expanded
                        && event.getNewFragment().toString().indexOf('\n') >= 0) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!ExpandableCodeField.this.expanded) {
                            toggle();
                        }
                    });
                }
            }
        });
        field = created;

        if (expanded) {
            // Let the field report its content size so the scroll pane knows when to scroll.
            JBScrollPane pane = new JBScrollPane(new ScrollingView(created));
            pane.setBorder(BorderFactory.createEmptyBorder());
            pane.setViewportBorder(null);
            pane.setPreferredSize(new Dimension(FIELD_WIDTH, EXPANDED_HEIGHT));
            pane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            pane.getVerticalScrollBar().setUnitIncrement(16);
            host = pane;
        } else {
            created.setPreferredSize(new Dimension(FIELD_WIDTH, COLLAPSED_HEIGHT));
            host = created;
        }
        add(host, BorderLayout.CENTER);

        if (caret >= 0) {
            field.setCaretPosition(Math.min(caret, field.getDocument().getTextLength()));
        }
        toggleButton.setIcon(expanded ? AllIcons.General.ArrowUp : AllIcons.General.ArrowDown);
        revalidate();
        repaint();
        ApplicationManager.getApplication().invokeLater(this::installCaretScroller);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        installEnterDispatcher();
        installCaretScroller();
    }

    @Override
    public void removeNotify() {
        if (dispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removeKeyEventDispatcher(enterDispatcher);
            dispatcherInstalled = false;
        }
        super.removeNotify();
    }

    /**
     * Enter must never fire the dialog's default button (Create / Save) while the caret is in the
     * code editor. The dispatcher runs before the dialog's own Enter handling; the editor itself
     * then receives the key normally, so a multi-line block still gets its line break.
     */
    private void installEnterDispatcher() {
        if (dispatcherInstalled) {
            return;
        }
        enterDispatcher = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_ENTER) {
                return false;
            }
            // Shift+Enter (new line), Alt+Enter (intentions) and Ctrl+Enter are editor / dialog
            // shortcuts; only the plain Enter key is stopped from firing the default button.
            if (e.isShiftDown() || e.isControlDown() || e.isMetaDown() || e.isAltDown()) {
                return false;
            }
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (owner == null || !SwingUtilities.isDescendingFrom(owner, field)) {
                return false;
            }
            if (!expanded) {
                // One-line mode: a line break is not possible, and the dialog must not submit.
                return true;
            }
            Editor editor = field.getEditor();
            if (editor != null) {
                EditorActionManager.getInstance()
                        .getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
                        .execute(editor, editor.getCaretModel().getCurrentCaret(), null);
            }
            // Consume either way: the event never reaches the dialog's default button.
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(enterDispatcher);
        dispatcherInstalled = true;
    }

    /** Keeps the caret visible when the block is longer than the scroll pane. */
    private void installCaretScroller() {
        Editor editor = field.getEditor();
        if (!expanded || editor == null || editor == caretEditor
                || !(host instanceof JScrollPane)) {
            return;
        }
        caretEditor = editor;
        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent event) {
                scrollCaretIntoView();
            }
        });
    }

    private void scrollCaretIntoView() {
        Editor editor = field.getEditor();
        if (!expanded || editor == null || !(host instanceof JScrollPane)) {
            return;
        }
        JViewport viewport = ((JScrollPane) host).getViewport();
        Point point = editor.offsetToXY(editor.getCaretModel().getOffset());
        if (point == null) {
            return;
        }
        Rectangle caret = new Rectangle(point.x, point.y, 2, editor.getLineHeight());
        Rectangle inView = SwingUtilities.convertRectangle(
                editor.getContentComponent(), caret, viewport.getView());
        Component view = viewport.getView();
        if (view instanceof JComponent) {
            ((JComponent) view).scrollRectToVisible(inView);
        }
    }

    private void toggle() {
        expanded = !expanded;
        rebuild();
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window instanceof Dialog) {
            ((Dialog) window).pack();
        } else if (window instanceof Frame) {
            ((Frame) window).pack();
        }
        field.requestFocusInWindow();
        installCaretScroller();
    }

    public void setPlaceholder(String text) {
        placeholder = text == null ? "" : text;
        field.setPlaceholder(placeholder);
        field.setShowPlaceholderWhenFocused(false);
    }

    public String getText() {
        return field.getText();
    }

    public void setText(String text) {
        field.setText(text == null ? "" : text);
    }

    /** The live editor component (changes when the field is expanded / collapsed). */
    public EditorTextField getField() {
        return field;
    }

    /** Exposed for tests / callers that want to know whether the field shows a text area. */
    public boolean isExpanded() {
        return expanded;
    }

    /**
     * Hosts the field and reports the height of its content, so the surrounding scroll pane gets a
     * vertical scrollbar as soon as the block is taller than {@link #EXPANDED_HEIGHT}.
     */
    private static final class ScrollingView extends JPanel implements Scrollable {

        private final EditorTextField field;

        ScrollingView(EditorTextField field) {
            super(new BorderLayout());
            setOpaque(false);
            this.field = field;
            add(field, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension inner = field.getPreferredSize();
            // Match the viewport width so the horizontal bar only appears for long lines.
            int width = FIELD_WIDTH;
            Component viewport = SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (viewport != null && viewport.getWidth() > 0) {
                width = viewport.getWidth();
            }
            return new Dimension(Math.max(width, inner.width),
                    Math.max(EXPANDED_HEIGHT, inner.height));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(@NotNull Rectangle visibleRect, int orientation,
                                              int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(@NotNull Rectangle visibleRect, int orientation,
                                               int direction) {
            return Math.max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
