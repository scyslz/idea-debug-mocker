package com.debugmock.ui;

import com.debugmock.psi.ExecutionContext;
import com.debugmock.psi.LocalVariables;
import com.debugmock.psi.MethodInfo;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaCodeFragment;
import com.intellij.psi.JavaCodeFragmentFactory;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.PsiDocumentManagerEx;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.LanguageTextField;
import org.jetbrains.annotations.NotNull;

/**
 * Builds the documents used by the rule dialog's code editors.
 *
 * <p>The editor is backed by a real {@link JavaCodeFragment} whose context is the target method
 * body, so the IDE treats it exactly like a debugger "Evaluate Expression" editor: Java syntax and
 * semantic (variable) highlighting, context-aware completion of the method parameters, and
 * automatic imports. The fragment is created with an event-system-enabled document, which is what
 * allows {@link PsiDocumentManager} to hand out its document and what keeps the background
 * highlighter (semantic colouring) working.</p>
 */
public final class StatementEditorUtil {

    private static final String COMMON_IMPORTS =
            "import java.util.*;\n" +
            "import java.util.concurrent.*;\n" +
            "import java.util.function.*;\n" +
            "import java.util.stream.*;\n" +
            "import java.io.*;\n" +
            "import java.math.*;\n" +
            "import java.nio.file.*;\n" +
            "import java.time.*;\n" +
            "import java.text.*;\n" +
            "import java.net.*;\n";

    private StatementEditorUtil() {
    }

    /** Document + file type for an editor, plus the fragment that must stay alive with it. */
    public static final class CodeDocument {
        private final Project project;
        private final Document document;
        private final FileType fileType;
        private final JavaCodeFragment fragment;
        private final String initialText;

        CodeDocument(Project project, Document document, FileType fileType,
                     JavaCodeFragment fragment, String initialText) {
            this.project = project;
            this.document = document;
            this.fileType = fileType;
            this.fragment = fragment;
            this.initialText = initialText;
        }

        public Document getDocument() {
            return document;
        }

        public FileType getFileType() {
            return fileType;
        }

        public JavaCodeFragment getFragment() {
            return fragment;
        }

        /**
         * Creates the Swing editor.
         *
         * <p>Uses a plain {@link EditorTextField} (not {@code LanguageTextField}): the latter
         * installs a lexer-only highlighter that would hide the IDE's semantic highlighting, so
         * method parameters would not be coloured as variables. With the Java file type the editor
         * gets the full IDE highlighter driven by the fragment PSI.</p>
         *
         * <p>Auto-popup completion is installed explicitly: the editor is marked as
         * "always auto popup" and a document listener schedules completion after identifier
         * characters, so typing {@code na} shows {@code name : String} without Ctrl+Space — the
         * same behaviour as the Evaluate Expression editor.</p>
         */
        public EditorTextField createField(boolean oneLine, String placeholder) {
            final Project targetProject = project;
            EditorTextField field = new EditorTextField(document, project, fileType, false, oneLine) {
                /** Registered once per field instance, not once per created editor. */
                private boolean autoPopupInstalled;

                @Override
                protected EditorEx createEditor() {
                    EditorEx editor = super.createEditor();
                    // Same configuration the platform's own Evaluate Expression editor uses.
                    editor.setEmbeddedIntoDialogWrapper(true);
                    // Bypass the global auto-popup preference so the dialog always behaves like
                    // Evaluate Expression, even if automatic completion is off for normal editors.
                    editor.putUserData(AutoPopupController.ALWAYS_AUTO_POPUP, Boolean.TRUE);
                    if (!autoPopupInstalled) {
                        autoPopupInstalled = true;
                        addDocumentListener(new DocumentListener() {
                            @Override
                            public void documentChanged(@NotNull DocumentEvent event) {
                                if (!isCompletionTrigger(event.getNewFragment())
                                        || targetProject.isDisposed()) {
                                    return;
                                }
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    Editor current = getEditor();
                                    if (current == null || current.isDisposed()) {
                                        return;
                                    }
                                    if (!current.getContentComponent().isShowing()) {
                                        return;
                                    }
                                    AutoPopupController.getInstance(targetProject)
                                            .scheduleAutoPopup(current);
                                });
                            }
                        });
                    }
                    return editor;
                }
            };
            field.setPlaceholder(placeholder == null ? "" : placeholder);
            field.setShowPlaceholderWhenFocused(false);
            return field;
        }
    }

    /** Characters that should open the completion popup automatically. */
    private static boolean isCompletionTrigger(CharSequence inserted) {
        if (inserted.length() != 1) {
            // Paste / multi-char input: do not pop up.
            return false;
        }
        char c = inserted.charAt(0);
        return Character.isJavaIdentifierPart(c) || c == '.' || c == '"' || c == '<';
    }

    /** Java statement block (Value / Code editor). */
    public static CodeDocument createBlock(@NotNull Project project, @NotNull MethodInfo info,
                                           String initialText) {
        return createBlock(project, info, initialText, 0);
    }

    /** Java statement block with the rule's source line (locals in scope become available). */
    public static CodeDocument createBlock(@NotNull Project project, @NotNull MethodInfo info,
                                           String initialText, int line) {
        return create(project, info, initialText, false, line);
    }

    /** Java boolean expression (Condition editor). */
    public static CodeDocument createCondition(@NotNull Project project, @NotNull MethodInfo info,
                                               String initialText) {
        return createCondition(project, info, initialText, 0);
    }

    /** Java boolean expression with the rule's source line. */
    public static CodeDocument createCondition(@NotNull Project project, @NotNull MethodInfo info,
                                               String initialText, int line) {
        return create(project, info, initialText, true, line);
    }

    private static CodeDocument create(Project project, MethodInfo info, String initialText,
                                       boolean expression, int line) {
        String text = initialText == null ? "" : initialText;
        // This runs while the rule dialog is being built on the EDT; every PSI access below
        // (including the synthetic context and the imports) needs a read action.
        try {
            return ReadAction.compute(() -> createUnderReadAction(project, info, text, expression, line));
        } catch (RuntimeException e) {
            System.err.println("[DebugMock] Java code fragment creation failed: " + e);
            // Fall through to a plain text field when PSI fragment creation is unavailable.
        }
        return plain(project, text);
    }

    private static CodeDocument createUnderReadAction(Project project, MethodInfo info, String text,
                                                      boolean expression, int line) {
        PsiMethod method = info.getMethod();
        if (method == null) {
            return plain(project, text);
        }
        // A synthetic context declares exactly the variables the generated block will have:
        // the method parameters, the locals in scope at the rule line and the injected
        // helpers (args / threadName / now / self). It extends the target class so fields and
        // methods resolve as they do inside the original method body.
        PsiElement contextElement = syntheticContext(project, info, expression, line);
        if (contextElement == null) {
            // Fallback: the real method body (parameters and fields resolve, helpers do not).
            contextElement = method.getBody();
        }
        if (contextElement != null) {
            JavaCodeFragment fragment = expression
                    ? JavaCodeFragmentFactory.getInstance(project).createExpressionCodeFragment(
                            text, contextElement, PsiType.BOOLEAN, true)
                    : JavaCodeFragmentFactory.getInstance(project).createCodeBlockCodeFragment(
                            text, contextElement, true);
            fragment.addImportsFromString(imports(info));

            PsiDocumentManager manager = PsiDocumentManager.getInstance(project);
            Document document = manager.getDocument(fragment);
            if (document == null && manager instanceof PsiDocumentManagerEx) {
                document = ((PsiDocumentManagerEx) manager)
                        .getDocumentForNonPhysicalLightFile(fragment);
            }
            if (document == null && manager instanceof PsiDocumentManagerEx) {
                // Last resort: an event-system document with the fragment attached, so
                // completion still resolves the fragment context (parameters/locals).
                document = EditorFactory.getInstance().createDocument(text, true, false);
                ((PsiDocumentManagerEx) manager).associatePsi(document, fragment);
            }
            if (document != null) {
                return new CodeDocument(project, document, JavaFileType.INSTANCE, fragment, text);
            }
        }
        return plain(project, text);
    }

    /**
     * Builds a light Java file containing a method that declares every variable the block will
     * have, and returns that method's body to be used as the code fragment context.
     */
    private static PsiElement syntheticContext(Project project, MethodInfo info, boolean expression,
                                               int line) {
        try {
            PsiMethod method = info.getMethod();
            if (method == null) {
                return null;
            }
            ExecutionContext context = ExecutionContext.of(info, LocalVariables.inScope(method, line));
            PsiFile containingFile = method.getContainingFile();
            String packageName = containingFile instanceof PsiJavaFile
                    ? ((PsiJavaFile) containingFile).getPackageName() : "";
            String source = context.contextClassSource(packageName, imports(info));
            PsiFile contextFile = PsiFileFactory.getInstance(project)
                    .createFileFromText("DebugMockContext.java", JavaFileType.INSTANCE, source);
            PsiMethod contextMethod = findContextMethod(contextFile, expression);
            return contextMethod == null ? null : contextMethod.getBody();
        } catch (RuntimeException e) {
            System.err.println("[DebugMock] synthetic context failed: " + e);
            return null;
        }
    }

    private static PsiMethod findContextMethod(PsiFile file, boolean expression) {
        if (!(file instanceof PsiJavaFile)) {
            return null;
        }
        PsiClass[] classes = ((PsiJavaFile) file).getClasses();
        if (classes.length == 0) {
            return null;
        }
        String wanted = expression ? "__condition" : "__execute";
        for (PsiMethod candidate : classes[0].getMethods()) {
            if (wanted.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    /** Imports copied from the target file plus common JDK packages. */
    public static String imports(MethodInfo info) {
        StringBuilder sb = new StringBuilder(COMMON_IMPORTS);
        if (info != null && info.getMethod() != null) {
            PsiFile file = info.getMethod().getContainingFile();
            if (file instanceof PsiJavaFile) {
                PsiImportList list = ((PsiJavaFile) file).getImportList();
                if (list != null) {
                    for (PsiImportStatement statement : list.getImportStatements()) {
                        String text = statement.getText();
                        if (text != null && !text.isEmpty()) {
                            sb.append(text).append('\n');
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Fallback when no fragment context is available: still a Java-backed document so keywords and
     * literals are highlighted (completion falls back to the plain Java file).
     */
    private static CodeDocument plain(Project project, String text) {
        // LanguageTextField.createDocument builds a light PSI file through PsiFileFactory, so it
        // needs read access too — this fallback runs on the EDT when fragment creation fails.
        CodeDocument result = null;
        try {
            result = ReadAction.compute(() -> {
                Document document = LanguageTextField.createDocument(text, JavaLanguage.INSTANCE,
                        project, new LanguageTextField.SimpleDocumentCreator());
                if (document != null) {
                    return new CodeDocument(project, document, JavaFileType.INSTANCE, null, text);
                }
                return null;
            });
        } catch (RuntimeException ignored) {
            // Fall through to a non-PSI document.
        }
        if (result != null) {
            return result;
        }
        return new CodeDocument(project, EditorFactory.getInstance().createDocument(text),
                com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE, null, text);
    }
}
