package com.debugmock.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites mocked methods with ASM.
 *
 * <p>Rules with {@code lineNumber == 0} are inserted at method entry; rules with a positive line
 * are inserted right after that line's {@code LineNumberNode} (after any stack-map frame, so the
 * frame table stays valid). Line rules are only inserted where the operand stack is empty, which
 * is the case at statement starts — the IDE only creates line rules there.</p>
 *
 * <p>When a rule matches, the method returns / throws / runs the compiled block. An EXECUTE block
 * that falls through returns a sentinel, and the original body continues.</p>
 */
public final class MockTransformer implements ClassFileTransformer {

    private static final String RUNTIME = "com/debugmock/agent/MockRuntime";

    private final Map<String, List<MockRule>> rulesByClass = new HashMap<>();

    public MockTransformer(List<MockRule> rules) {
        for (MockRule rule : rules) {
            if (!rule.enabled || rule.className == null || rule.methodName == null || rule.signature == null) {
                continue;
            }
            String internal = rule.className.replace('.', '/');
            rulesByClass.computeIfAbsent(internal, k -> new ArrayList<>()).add(rule);
        }
    }

    public boolean isTarget(String dottedClassName) {
        return dottedClassName != null && rulesByClass.containsKey(dottedClassName.replace('.', '/'));
    }

    /** Dotted names of all classes this transformer modifies (used for hot reload). */
    public Set<String> targets() {
        Set<String> result = new HashSet<>();
        for (String internal : rulesByClass.keySet()) {
            result.add(internal.replace('/', '.'));
        }
        return result;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        List<MockRule> rules = className == null ? null : rulesByClass.get(className);
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            List<MethodNode> instrumented = new ArrayList<>();
            for (MethodNode method : classNode.methods) {
                List<MockRule> methodRules = findRules(rules, method.name, method.desc);
                if (methodRules.isEmpty()
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    continue;
                }
                instrument(classNode, method, methodRules, loader);
                instrumented.add(method);
            }
            if (instrumented.isEmpty()) {
                return null;
            }
            // A reader-backed ClassWriter ignores inserted compressed frames, and COMPUTE_FRAMES
            // would need application classes that may be invisible to the agent's classloader.
            // Writing with a plain writer keeps the original frames and emits our F_SAME frames;
            // the analyzer below supplies the new max stack / locals for the injected code.
            for (MethodNode method : instrumented) {
                refreshMaxStack(classNode, method);
            }
            ClassWriter writer = new ClassWriter(0);
            classNode.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[DebugMock] failed to transform " + className + ": " + t);
            return null;
        }
    }

    private static List<MockRule> findRules(List<MockRule> rules, String name, String descriptor) {
        List<MockRule> result = new ArrayList<>();
        for (MockRule rule : rules) {
            if (rule.methodName.equals(name) && rule.signature.equals(descriptor)) {
                result.add(rule);
            }
        }
        return result;
    }

    private static void instrument(ClassNode classNode, MethodNode method, List<MockRule> methodRules,
                                   ClassLoader loader) {
        List<MockRule> entryRules = new ArrayList<>();
        Map<Integer, List<MockRule>> lineRules = new LinkedHashMap<>();
        for (MockRule rule : methodRules) {
            if (rule.lineNumber > 0) {
                lineRules.computeIfAbsent(rule.lineNumber, k -> new ArrayList<>()).add(rule);
            } else {
                entryRules.add(rule);
            }
        }

        if (!entryRules.isEmpty()) {
            InsnList injected = buildInjectedRules(entryRules, method, method.access, method.desc,
                    initialLocals(classNode.name, method));
            AbstractInsnNode firstReal = firstRealInstruction(method.instructions);
            if (firstReal == null) {
                method.instructions.add(injected);
            } else {
                method.instructions.insertBefore(firstReal, injected);
            }
        }
        if (!lineRules.isEmpty()) {
            applyLineRules(classNode, method, lineRules, loader);
        }
    }

    private static void applyLineRules(ClassNode classNode, MethodNode method,
                                       Map<Integer, List<MockRule>> lineRules, ClassLoader loader) {
        // Line numbers are stored as absolute source lines. When the app was not recompiled after
        // the source moved, the stored line no longer exists in the bytecode; fall back to the
        // statement ordinal so the rule still lands on the same statement.
        lineRules = relocateMissingLines(method, lineRules);

        Frame<BasicValue>[] frames;
        try {
            // SimpleVerifier keeps the real reference types of locals (BasicInterpreter erases
            // them all to Object, which makes our injected full frames fail verification).
            SimpleVerifier verifier = new SimpleVerifier();
            if (loader != null) {
                verifier.setClassLoader(loader);
            }
            frames = new Analyzer<>(verifier).analyze(classNode.name, method);
        } catch (Throwable t) {
            System.err.println("[DebugMock] cannot analyse " + classNode.name + "." + method.name
                    + " for line rules: " + t);
            return;
        }

        List<Insertion> insertions = new ArrayList<>();
        InsnList instructions = method.instructions;
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode node = instructions.get(i);
            if (!(node instanceof LineNumberNode)) {
                continue;
            }
            int line = ((LineNumberNode) node).line;
            List<MockRule> atLine = lineRules.get(line);
            if (atLine == null || atLine.isEmpty()) {
                continue;
            }

            // The stack-map frame must stay immediately after the line number, so insert after it.
            int next = i + 1;
            AbstractInsnNode insertAfter = node;
            while (next < instructions.size() && instructions.get(next) instanceof FrameNode) {
                insertAfter = instructions.get(next);
                next++;
            }
            if (next >= instructions.size() || next >= frames.length || isPseudo(instructions.get(next))) {
                continue;
            }
            Frame<BasicValue> frame = frames[next];
            if (frame == null || frame.getStackSize() != 0) {
                System.err.println("[DebugMock] rule at " + classNode.name + ":" + line
                        + " skipped: the line is not a statement start");
                continue;
            }
            insertions.add(new Insertion(insertAfter, atLine, frameLocals(frame)));
        }

        for (Insertion insertion : insertions) {
            instructions.insert(insertion.after,
                    buildInjectedRules(insertion.rules, method, method.access, method.desc,
                            insertion.locals));
        }
    }

    /**
     * Remaps rules whose stored line is absent from this class file to the same statement ordinal.
     * Only used as a safety net for rules.json written before a line above the rule was removed
     * while the running class was still the old compilation.
     */
    private static Map<Integer, List<MockRule>> relocateMissingLines(
            MethodNode method, Map<Integer, List<MockRule>> lineRules) {
        List<Integer> knownLines = new ArrayList<>();
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof LineNumberNode) {
                int line = ((LineNumberNode) node).line;
                if (!knownLines.contains(line)) {
                    knownLines.add(line);
                }
            }
        }
        Map<Integer, List<MockRule>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<MockRule>> entry : lineRules.entrySet()) {
            int line = entry.getKey();
            if (!knownLines.contains(line)) {
                Integer remapped = null;
                for (MockRule rule : entry.getValue()) {
                    if (rule.lineOrdinal >= 0 && rule.lineOrdinal < knownLines.size()) {
                        remapped = knownLines.get(rule.lineOrdinal);
                        break;
                    }
                }
                if (remapped != null) {
                    System.err.println("[DebugMock] rule line " + line + " missing in "
                            + method.name + "; using statement ordinal " + remapped);
                    line = remapped;
                }
            }
            result.computeIfAbsent(line, k -> new ArrayList<>()).addAll(entry.getValue());
        }
        return result;
    }

    private static final class Insertion {
        final AbstractInsnNode after;
        final List<MockRule> rules;
        final Object[] locals;

        Insertion(AbstractInsnNode after, List<MockRule> rules, Object[] locals) {
            this.after = after;
            this.rules = rules;
            this.locals = locals;
        }
    }

    private static InsnList buildInjectedRules(List<MockRule> rules, MethodNode method, int access,
                                               String descriptor, Object[] locals) {
        MethodNode builder = new MethodNode(Opcodes.ASM9, access, "__debugmock_rule",
                descriptor, null, null);
        for (MockRule rule : rules) {
            emitRule(rule, method, access, descriptor, builder, locals);
        }
        return builder.instructions;
    }

    /** Emits: if (matches(condition, args, parameterNames, self)) { mock behavior; } */
    private static void emitRule(MockRule rule, MethodNode method, int access, String methodDescriptor,
                                 MethodVisitor mv, Object[] locals) {
        // Every log line produced while this rule runs names the method and the line.
        mv.visitLdcInsn(ruleLabel(rule));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "setCurrentRule",
                "(Ljava/lang/String;)V", false);

        Label skip = new Label();
        String condition = rule.condition == null ? "" : rule.condition.trim();
        if (condition.isEmpty()) {
            mv.visitInsn(Opcodes.ICONST_1);
        } else {
            mv.visitLdcInsn(condition);
            emitArgumentsArray(access, methodDescriptor, mv);
            emitParameterNamesArray(rule, mv);
            if ((access & Opcodes.ACC_STATIC) == 0) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "matches",
                    "(Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;Ljava/lang/Object;)Z",
                    false);
        }
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        emitMock(rule, method, access, methodDescriptor, mv, locals);
        mv.visitLabel(skip);
        mv.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]);
    }

    private static String ruleLabel(MockRule rule) {
        String action = rule.action == null ? "" : rule.action.toUpperCase(Locale.ROOT);
        return (rule.className == null ? "" : rule.className) + "#" + rule.methodName
                + (rule.lineNumber > 0 ? " line " + rule.lineNumber : " entry")
                + (action.isEmpty() ? "" : " " + action);
    }

    private static void emitParameterNamesArray(MockRule rule, MethodVisitor mv) {
        String[] names = rule.parameterNames;
        int count = names == null ? 0 : names.length;
        pushInt(mv, count);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
        for (int i = 0; i < count; i++) {
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, i);
            mv.visitLdcInsn(names[i] == null ? "" : names[i]);
            mv.visitInsn(Opcodes.AASTORE);
        }
    }

    /** Builds an Object[] of the method arguments for condition evaluation. */
    private static void emitArgumentsArray(int access, String methodDescriptor, MethodVisitor mv) {
        Type[] argumentTypes = Type.getArgumentTypes(methodDescriptor);
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        pushInt(mv, argumentTypes.length);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < argumentTypes.length; i++) {
            Type type = argumentTypes[i];
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, i);
            int local = isStatic ? i : i + 1;
            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), local);
            box(mv, type);
            mv.visitInsn(Opcodes.AASTORE);
        }
    }

    /**
     * Builds the values array passed to {@link MockRuntime#executeCompiled}: {@code [args, self,
     * param0, param1, ...]}.
     */
    private static void emitExecuteValues(int access, String methodDescriptor, MethodVisitor mv,
                                          MockRule rule, MethodNode method, Object[] frameLocals) {
        Type[] argumentTypes = Type.getArgumentTypes(methodDescriptor);
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        int localCount = rule.localNames == null ? 0 : rule.localNames.length;
        pushInt(mv, 2 + argumentTypes.length + localCount);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

        mv.visitInsn(Opcodes.DUP);
        pushInt(mv, 0);
        emitArgumentsArray(access, methodDescriptor, mv);
        mv.visitInsn(Opcodes.AASTORE);

        mv.visitInsn(Opcodes.DUP);
        pushInt(mv, 1);
        if (isStatic) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }
        mv.visitInsn(Opcodes.AASTORE);

        for (int i = 0; i < argumentTypes.length; i++) {
            Type type = argumentTypes[i];
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, 2 + i);
            int local = isStatic ? i : i + 1;
            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), local);
            box(mv, type);
            mv.visitInsn(Opcodes.AASTORE);
        }

        // Locals in scope at the rule line: appended after the parameters, in the same order the
        // IDE used when it declared them in the compiled block.
        for (int i = 0; i < localCount; i++) {
            String name = rule.localNames[i];
            String descriptor = rule.localTypes != null && i < rule.localTypes.length
                    ? rule.localTypes[i] : null;
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, 2 + argumentTypes.length + i);
            int slot = findLocalSlot(method, frameLocals, name, descriptor);
            if (slot < 0) {
                // Not available (compiled without debug info, or not initialised on this path):
                // pass a type-appropriate default so the block still runs.
                pushLocalDefault(mv, descriptor);
            } else {
                Type type = Type.getType(descriptor);
                mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);
                box(mv, type);
            }
            mv.visitInsn(Opcodes.AASTORE);
        }
    }

    /** JVM slot of a local, preferring the innermost declaration that is live at this point. */
    private static int findLocalSlot(MethodNode method, Object[] frameLocals,
                                     String name, String descriptor) {
        if (method.localVariables == null || name == null || frameLocals == null) {
            return -1;
        }
        int best = -1;
        int bestStart = -1;
        for (org.objectweb.asm.tree.LocalVariableNode local : method.localVariables) {
            if (!name.equals(local.name)) {
                continue;
            }
            if (descriptor != null && local.desc != null && !descriptor.equals(local.desc)) {
                continue;
            }
            int index = local.index;
            if (index < 0 || index >= frameLocals.length) {
                continue;
            }
            Object frameType = frameLocals[index];
            if (frameType == null || Integer.valueOf(Opcodes.TOP).equals(frameType)) {
                continue; // not initialised on this path
            }
            int start = method.instructions.indexOf(local.start);
            if (start > bestStart) {
                bestStart = start;
                best = index;
            }
        }
        return best;
    }

    private static void pushLocalDefault(MethodVisitor mv, String descriptor) {
        Type type = descriptor == null ? null : Type.getType(descriptor);
        if (type == null || type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            return;
        }
        switch (type.getSort()) {
            case Type.LONG:
                mv.visitInsn(Opcodes.LCONST_0);
                break;
            case Type.FLOAT:
                mv.visitInsn(Opcodes.FCONST_0);
                break;
            case Type.DOUBLE:
                mv.visitInsn(Opcodes.DCONST_0);
                break;
            default:
                mv.visitInsn(Opcodes.ICONST_0);
                break;
        }
        box(mv, type);
    }

    private static void box(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
                break;
            case Type.BYTE:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf",
                        "(B)Ljava/lang/Byte;", false);
                break;
            case Type.CHAR:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf",
                        "(C)Ljava/lang/Character;", false);
                break;
            case Type.SHORT:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf",
                        "(S)Ljava/lang/Short;", false);
                break;
            case Type.INT:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false);
                break;
            case Type.LONG:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
                break;
            case Type.FLOAT:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false);
                break;
            case Type.DOUBLE:
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
                break;
            default:
                break;
        }
    }

    private static void emitMock(MockRule rule, MethodNode method, int access,
                                 String methodDescriptor, MethodVisitor mv, Object[] locals) {
        String action = rule.action == null ? "RETURN" : rule.action.toUpperCase(Locale.ROOT);

        // Prefer the generated Java block for every non-SKIP action (full language support). Keep
        // the 3-argument call to MockRuntime so a hot-swapped plugin still works with an agent jar
        // built before the runtime-compilation change: the source is looked up from the rules
        // loaded by the agent, not passed through the bytecode.
        boolean hasSource = rule.compiledSource != null && !rule.compiledSource.isEmpty();
        boolean hasBytecode = rule.compiledBytecode != null && !rule.compiledBytecode.isEmpty();
        if (!"SKIP".equals(action) && (hasSource || hasBytecode)
                && rule.compiledClassName != null && !rule.compiledClassName.isEmpty()) {
            String returnDescriptor = methodDescriptor.substring(methodDescriptor.indexOf(')') + 1);
            mv.visitLdcInsn(hasBytecode ? rule.compiledBytecode : "");
            mv.visitLdcInsn(rule.compiledClassName);
            emitExecuteValues(access, methodDescriptor, mv, rule, method, locals);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "executeCompiled",
                    "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            if (rule.compiledTerminates) {
                emitUnboxReturn(returnDescriptor, mv);
            } else {
                // The block may fall through: continue with the original body in that case.
                Label cont = new Label();
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "isContinue",
                        "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(Opcodes.IFNE, cont);
                emitUnboxReturn(returnDescriptor, mv);
                mv.visitLabel(cont);
                // The branch lands here with the result still on the stack. The frame must be
                // at this label (not after the POP — that would share the next label's offset
                // and ASM would drop it).
                mv.visitFrame(Opcodes.F_NEW, locals.length, locals, 1,
                        new Object[]{"java/lang/Object"});
                mv.visitInsn(Opcodes.POP);
            }
            return;
        }

        if ("EXECUTE".equals(action)) {
            // Fallback: legacy interpreted statements.
            String statement = rule.statement == null ? "" : rule.statement;
            for (String part : statement.split(";")) {
                String stmt = part.trim();
                if (stmt.isEmpty()) {
                    continue;
                }
                if (stmt.startsWith("return ")) {
                    emitReturnExpression(stmt.substring(7).trim(), access, methodDescriptor, mv);
                } else if (stmt.startsWith("throw ")) {
                    mv.visitLdcInsn(stmt.substring(6).trim());
                    emitArgumentsArray(access, methodDescriptor, mv);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "evalThrowable",
                            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Throwable;", false);
                    mv.visitInsn(Opcodes.ATHROW);
                } else {
                    mv.visitLdcInsn(stmt);
                    emitArgumentsArray(access, methodDescriptor, mv);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "executeStatement",
                            "(Ljava/lang/String;[Ljava/lang/Object;)V", false);
                }
            }
            return;
        }
        if ("SKIP".equals(action)) {
            mv.visitInsn(Opcodes.RETURN);
            return;
        }
        if ("THROW".equals(action)) {
            String exception = rule.exceptionClass == null || rule.exceptionClass.trim().isEmpty()
                    ? "java.lang.RuntimeException" : rule.exceptionClass.trim();
            String message = rule.exceptionMessage == null ? "" : rule.exceptionMessage;
            String internal = exception.replace('.', '/');
            mv.visitTypeInsn(Opcodes.NEW, internal);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(message);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internal, "<init>", "(Ljava/lang/String;)V", false);
            mv.visitInsn(Opcodes.ATHROW);
            return;
        }
        emitReturn(methodDescriptor.substring(methodDescriptor.indexOf(')') + 1), rule.returnValue, mv);
    }

    /** Evaluates a return expression to Object, unboxes it to the method's return type and returns. */
    private static void emitReturnExpression(String expression, int access,
                                             String methodDescriptor, MethodVisitor mv) {
        String returnDescriptor = methodDescriptor.substring(methodDescriptor.indexOf(')') + 1);
        mv.visitLdcInsn(expression);
        emitArgumentsArray(access, methodDescriptor, mv);
        mv.visitLdcInsn(returnDescriptor);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "evalValue",
                "(Ljava/lang/String;[Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);

        emitUnboxReturn(returnDescriptor, mv);
    }

    /** Converts an Object on the stack into the method's return type and emits the return. */
    private static void emitUnboxReturn(String returnDescriptor, MethodVisitor mv) {
        switch (returnDescriptor) {
            case "V":
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.RETURN);
                return;
            case "Z":
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                mv.visitInsn(Opcodes.IRETURN);
                return;
            case "B":
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false);
                mv.visitInsn(Opcodes.IRETURN);
                return;
            case "S":
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false);
                mv.visitInsn(Opcodes.IRETURN);
                return;
            case "C":
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                mv.visitInsn(Opcodes.IRETURN);
                return;
            case "I":
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
                mv.visitInsn(Opcodes.IRETURN);
                return;
            case "J":
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
                mv.visitInsn(Opcodes.LRETURN);
                return;
            case "F":
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
                mv.visitInsn(Opcodes.FRETURN);
                return;
            case "D":
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
                mv.visitInsn(Opcodes.DRETURN);
                return;
            default:
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(returnDescriptor).getInternalName());
                mv.visitInsn(Opcodes.ARETURN);
        }
    }

    private static void emitReturn(String returnDescriptor, String value, MethodVisitor mv) {
        String raw = value == null ? "" : value.trim();
        switch (returnDescriptor) {
            case "Z":
                mv.visitInsn(Boolean.parseBoolean(raw) ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
                return;
            case "B":
            case "S":
            case "I":
            case "C":
                pushInt(mv, parseInt(raw));
                mv.visitInsn(Opcodes.IRETURN);
                return;
            case "J":
                mv.visitLdcInsn(parseLong(raw));
                mv.visitInsn(Opcodes.LRETURN);
                return;
            case "F":
                mv.visitLdcInsn(parseFloat(raw));
                mv.visitInsn(Opcodes.FRETURN);
                return;
            case "D":
                mv.visitLdcInsn(parseDouble(raw));
                mv.visitInsn(Opcodes.DRETURN);
                return;
            case "V":
                mv.visitInsn(Opcodes.RETURN);
                return;
            case "Ljava/lang/String;":
                mv.visitLdcInsn(value == null ? "" : value);
                mv.visitInsn(Opcodes.ARETURN);
                return;
            default:
                String typeName = returnDescriptor.startsWith("[")
                        ? returnDescriptor.replace('/', '.')
                        : returnDescriptor.substring(1, returnDescriptor.length() - 1).replace('/', '.');
                mv.visitLdcInsn(typeName);
                mv.visitLdcInsn(value == null ? "" : value);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/debugmock/agent/MockValues", "fromJson",
                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(returnDescriptor).getInternalName());
                mv.visitInsn(Opcodes.ARETURN);
        }
    }

    // ---------- stack-map helpers ----------

    private static Object[] initialLocals(String owner, MethodNode method) {
        List<Object> locals = new ArrayList<>();
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        if (!isStatic) {
            locals.add("<init>".equals(method.name) ? Opcodes.UNINITIALIZED_THIS : owner);
        }
        for (Type type : Type.getArgumentTypes(method.desc)) {
            locals.add(frameType(type));
            if (type.getSize() == 2) {
                locals.add(Opcodes.TOP);
            }
        }
        return locals.toArray();
    }

    private static Object[] frameLocals(Frame<BasicValue> frame) {
        Object[] locals = new Object[frame.getLocals()];
        for (int i = 0; i < locals.length; i++) {
            locals[i] = frameValue(frame.getLocal(i));
        }
        return locals;
    }

    private static Object frameValue(BasicValue value) {
        if (value == null) {
            return Opcodes.TOP;
        }
        Type type = value.getType();
        if (type == null) {
            return Opcodes.TOP;
        }
        if (type.getSort() == Type.OBJECT) {
            String internal = type.getInternalName();
            return "null".equals(internal) ? Opcodes.NULL : internal;
        }
        return frameType(type);
    }

    private static Object frameType(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return Opcodes.INTEGER;
            case Type.FLOAT:
                return Opcodes.FLOAT;
            case Type.LONG:
                return Opcodes.LONG;
            case Type.DOUBLE:
                return Opcodes.DOUBLE;
            case Type.ARRAY:
                return type.getDescriptor();
            case Type.OBJECT:
                return type.getInternalName();
            default:
                return Opcodes.TOP;
        }
    }

    /** Supplies max stack / locals for the injected instructions (we do not add new locals). */
    private static void refreshMaxStack(ClassNode classNode, MethodNode method) {
        try {
            Frame<BasicValue>[] frames = new Analyzer<>(new BasicInterpreter())
                    .analyze(classNode.name, method);
            int maxStack = 0;
            int maxLocals = 0;
            for (Frame<BasicValue> frame : frames) {
                if (frame == null) {
                    continue;
                }
                maxStack = Math.max(maxStack, frame.getStackSize());
                maxLocals = Math.max(maxLocals, frame.getLocals());
            }
            method.maxStack = Math.max(method.maxStack, maxStack);
            method.maxLocals = Math.max(method.maxLocals, maxLocals);
        } catch (Throwable ignored) {
            // The original method's frame data cannot be analysed (e.g. JSR/RET); a generous
            // margin is enough because the injected code uses a handful of stack slots only.
            method.maxStack += 16;
        }
    }

    private static boolean isPseudo(AbstractInsnNode node) {
        return node instanceof LabelNode || node instanceof LineNumberNode || node instanceof FrameNode;
    }

    private static AbstractInsnNode firstRealInstruction(InsnList instructions) {
        for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
            if (!isPseudo(node)) {
                return node;
            }
        }
        return null;
    }

    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static float parseFloat(String raw) {
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
