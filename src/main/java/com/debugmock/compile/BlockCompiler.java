package com.debugmock.compile;

import com.debugmock.psi.ExecutionContext;
import com.debugmock.psi.MethodInfo;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Compiles an EXECUTE code block into a class using the IDE's JDK and the project's full
 * classpath. The resulting bytecode is returned base64-encoded for storage in rules.json. This
 * lets users write real Java (if/for/locals/imports/any project class) instead of a limited
 * expression language.
 */
public final class BlockCompiler {

    private static final String PACKAGE = "com.debugmock.generated";
    private static final String METHOD = "__execute";

    private BlockCompiler() {
    }

    public static final class CompiledBlock {
        public final String className;
        public final String base64;
        /** Full generated Java source, stored in the rule so the app JVM can compile it at runtime. */
        public final String source;
        /** True when the block always returns / throws, so the original body must be skipped. */
        public final boolean terminates;

        CompiledBlock(String className, String base64, String source, boolean terminates) {
            this.className = className;
            this.base64 = base64;
            this.source = source;
            this.terminates = terminates;
        }
    }

    public static CompiledBlock compile(String statement, Project project) throws CompileException {
        return compileBody(statement, project, null, "return null;", false, null);
    }

    /**
     * Compiles the value/code field according to the chosen action. THROW wraps a bare exception
     * expression as a throw statement; RETURN wraps a bare value/JSON expression as a return. The
     * user may always write the full statement instead, which is detected and left unchanged.
     *
     * <p>The generated {@code __execute} method declares the method parameters by name (plus
     * {@code args} / {@code threadName} / {@code now} / {@code self}), so the code can reference
     * the target method's parameters directly.</p>
     */
    public static CompiledBlock compileForAction(String code,
                                                 com.debugmock.model.MockAction action,
                                                 Project project,
                                                 MethodInfo methodInfo) throws CompileException {
        return compileForAction(code, action, project, methodInfo, 0);
    }

    public static CompiledBlock compileForAction(String code,
                                                 com.debugmock.model.MockAction action,
                                                 Project project,
                                                 MethodInfo methodInfo,
                                                 int line) throws CompileException {
        String body = code.trim();
        if (action == com.debugmock.model.MockAction.THROW) {
            String lower = body.toLowerCase();
            if (!lower.startsWith("throw ") && !lower.contains("throw new")) {
                body = "throw " + body + ";";
            }
        } else if (action == com.debugmock.model.MockAction.RETURN) {
            String lower = body.toLowerCase();
            boolean hasControlFlow = lower.startsWith("return ") || lower.startsWith("throw ")
                    || lower.contains(" if (") || lower.contains("if (")
                    || lower.contains("for (") || lower.contains("while (") || lower.contains(";");
            if (!hasControlFlow) {
                com.intellij.psi.PsiType returnType = methodInfo == null || methodInfo.getMethod() == null
                        ? null : methodInfo.getMethod().getReturnType();
                if (returnType != null && isStructuredJson(body)
                        && com.debugmock.json.JsonExample.supports(returnType)) {
                    // A JSON example for an object return type is turned into real Java code that
                    // builds the object, so what the user fills in actually compiles and runs.
                    try {
                        body = com.debugmock.json.JsonToJava.convert(
                                body, returnType, methodInfo.getClassName());
                    } catch (com.debugmock.json.JsonToJava.ConversionException e) {
                        throw new CompileException(e.getMessage());
                    }
                } else {
                    body = "return " + jsonToJava(body) + ";";
                }
            }
        }
        ExecutionContext context = methodInfo == null ? null
                : ExecutionContext.of(methodInfo,
                        com.debugmock.psi.LocalVariables.inScope(methodInfo.getMethod(), line));
        boolean forceTerminate = action == com.debugmock.model.MockAction.RETURN
                || action == com.debugmock.model.MockAction.THROW;
        // EXECUTE blocks that fall through must let the original body continue, so the generated
        // block returns a private sentinel that the agent recognises.
        String trailingReturn = forceTerminate || context == null
                ? "return null;" : "return " + context.getContinueName() + ";";
        CompiledBlock block = compileBody(body, project, context, trailingReturn, forceTerminate, methodInfo);
        return new CompiledBlock(block.className, block.base64, block.source,
                forceTerminate || block.terminates);
    }

    /** Converts a JSON-looking literal into a Java string literal returned as-is. */
    private static String jsonToJava(String value) {
        String v = value.trim();
        if (v.startsWith("{") || v.startsWith("[")) {
            return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "") + "\"";
        }
        return v;
    }

    private static boolean isStructuredJson(String value) {
        String v = value.trim();
        return v.startsWith("{") || v.startsWith("[");
    }

    private static CompiledBlock compileBody(String statement, Project project,
                                             ExecutionContext context, String trailingReturn,
                                             boolean forceTerminate,
                                             MethodInfo methodInfo) throws CompileException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new CompileException("No Java compiler available. Run the IDE on a JDK, not a JRE.");
        }

        String simpleName = "Rule_" + UUID.randomUUID().toString().replace("-", "");
        // Classes in the default package cannot be referenced from a named package, so keep the
        // generated block in the default package when the target class lives there.
        String generatedPackage = context == null || context.getDeclaringClass().indexOf('.') < 0
                ? "" : PACKAGE;
        String className = generatedPackage.isEmpty() ? simpleName : generatedPackage + "." + simpleName;
        // Inherit the imports of the target Java file (plus common JDK packages) so the block can
        // use the same types as the method without manually repeating import statements.
        String imports = com.debugmock.ui.StatementEditorUtil.imports(methodInfo)
                + extractImports(statement);
        String body = removeImports(statement);

        // First try with the trailing return; if the user's code always returns/throws, that line
        // is unreachable, so retry without it and remember that the block always terminates.
        String successfulSource = source(className, imports, body, trailingReturn, context);
        CompileResult result = compileSource(compiler, project, className, successfulSource, null);
        boolean terminates = forceTerminate;
        if (!result.success && mentionsUnreachable(result)) {
            successfulSource = source(className, imports, body, null, context);
            result = compileSource(compiler, project, className, successfulSource, null);
            terminates = true;
        }
        if (!result.success) {
            throw new CompileException(result.message);
        }

        // The generated class is loaded by the *application* JVM, not by the IDE (which may run a
        // much newer JDK — e.g. IDE 25 vs. app 22). Lower the bytecode target as far as javac
        // allows so the block also loads on older runtimes. The agent itself targets Java 8.
        // A failure here is harmless: we simply keep the bytecode from the successful compile above.
        for (int release : targetReleases()) {
            CompileResult lower = compileSource(compiler, project, className, successfulSource, release);
            if (lower.success) {
                result = lower;
                break;
            }
        }
        return new CompiledBlock(className, result.base64, successfulSource, terminates);
    }

    /**
     * Bytecode levels to try, lowest first. javac of the running IDE supports every level from 8
     * up to the IDE's own feature version; we stop at the first one that accepts the block.
     */
    private static int[] targetReleases() {
        int max = Runtime.version().feature();
        int count = Math.max(0, max - 8 + 1);
        int[] releases = new int[count];
        for (int i = 0; i < count; i++) {
            releases[i] = 8 + i;
        }
        return releases;
    }

    private static String source(String className, String imports, String body, String trailingReturn,
                                 ExecutionContext context) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        String packageName = className.lastIndexOf('.') < 0
                ? "" : className.substring(0, className.lastIndexOf('.'));
        String signature = context == null
                ? "Object[] args, String threadName, long now"
                : context.compileSignature();
        StringBuilder sb = new StringBuilder();
        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n");
        }
        sb.append(imports);
        sb.append("public class ").append(simpleName).append(" {\n");
        sb.append("  public static Object ").append(METHOD)
                .append('(').append(signature).append(") throws Throwable {\n");
        sb.append(body).append('\n');
        if (trailingReturn != null) {
            sb.append("    ").append(trailingReturn).append('\n');
        }
        sb.append("  }\n}\n");
        return sb.toString();
    }

    private static String extractImports(String statement) {
        StringBuilder sb = new StringBuilder();
        for (String line : statement.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                sb.append(trimmed).append('\n');
            }
        }
        return sb.toString();
    }

    private static String removeImports(String statement) {
        StringBuilder sb = new StringBuilder();
        for (String line : statement.split("\\r?\\n")) {
            if (!line.trim().startsWith("import ")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static CompileResult compileSource(JavaCompiler compiler, Project project,
                                               String className, String source, Integer release) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("debugmock-block-");
            Path javaFile = dir.resolve(className.substring(className.lastIndexOf('.') + 1) + ".java");
            Files.write(javaFile, source.getBytes(StandardCharsets.UTF_8));

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            String classpath = projectClasspath(project);
            List<String> options = new ArrayList<>(Arrays.asList(
                    "-classpath", classpath, "-d", dir.toString(), "-proc:none"));
            if (release != null) {
                options.add("--release");
                options.add(release.toString());
                // --release 8+ warns about obsolete source/target values on newer javacs; those
                // warnings are never shown to the user, but keep the diagnostics list tidy.
                options.add("-Xlint:-options");
            }
            String sourcePath = projectSourcePath(project);
            if (!sourcePath.isEmpty()) {
                options.add("-sourcepath");
                options.add(sourcePath);
            }

            boolean success;
            try (StandardJavaFileManager fileManager =
                         compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                Iterable<? extends JavaFileObject> units =
                        fileManager.getJavaFileObjects(javaFile.toFile());
                Boolean result = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
                success = Boolean.TRUE.equals(result);
            }

            if (!success) {
                StringBuilder message = new StringBuilder("Compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        message.append("  line ").append(d.getLineNumber())
                                .append(": ").append(d.getMessage(null)).append('\n');
                    }
                }
                return new CompileResult(false, message.toString(), null);
            }

            Path classFile = dir.resolve(className.replace('.', File.separatorChar) + ".class");
            byte[] bytes = Files.readAllBytes(classFile);
            return new CompileResult(true, "", Base64.getEncoder().encodeToString(bytes));
        } catch (IOException e) {
            return new CompileResult(false, "Compilation I/O error: " + e.getMessage(), null);
        } finally {
            if (dir != null) {
                deleteRecursively(dir);
            }
        }
    }

    private static boolean mentionsUnreachable(CompileResult result) {
        return result.message.toLowerCase().contains("unreachable");
    }

    private static String projectClasspath(Project project) {
        List<String> paths = new ArrayList<>();
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            try {
                paths.addAll(ModuleRootManager.getInstance(module)
                        .orderEntries().compileOnly().getPathsList().getPathList());
            } catch (RuntimeException ignored) {
                // skip module whose classpath cannot be resolved
            }
        }
        return String.join(File.pathSeparator, paths);
    }

    /**
     * Source roots are given to javac as well, so blocks that reference project types still
     * compile when the project output is stale or has not been built yet.
     */
    private static String projectSourcePath(Project project) {
        List<String> paths = new ArrayList<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            try {
                for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
                    paths.add(root.getPath());
                }
            } catch (RuntimeException ignored) {
                // skip module whose source roots cannot be resolved
            }
        }
        return String.join(File.pathSeparator, paths);
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private static final class CompileResult {
        final boolean success;
        final String message;
        final String base64;

        CompileResult(boolean success, String message, String base64) {
            this.success = success;
            this.message = message;
            this.base64 = base64;
        }
    }

    public static final class CompileException extends Exception {
        public CompileException(String message) {
            super(message);
        }
    }
}
