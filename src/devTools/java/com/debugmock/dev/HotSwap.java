package com.debugmock.dev;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Redefines the already loaded DebugMock classes inside a running sandbox IDE (started with
 * {@code --debug-jvm}), so a code change is visible without restarting the IDE.
 *
 * <p>It attaches over JDWP, reads the freshly compiled {@code .class} files from the Gradle output
 * directory, and asks the target JVM to redefine every matching loaded class. The IDE is started
 * with {@code -XX:+AllowEnhancedClassRedefinition}, so new methods / fields are accepted as well;
 * only brand new classes can't be introduced this way (they simply stay "not loaded yet").</p>
 *
 * <p>Usage: {@code HotSwap <port> <classes-dir>}. Exits with status 1 when nothing could be
 * redefined, so the Gradle task can tell the developer to restart.</p>
 */
public final class HotSwap {

    private HotSwap() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: HotSwap <debug-port> <classes-directory>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        Path classesDir = Paths.get(args[1]).toAbsolutePath().normalize();

        Map<String, byte[]> compiled = readClasses(classesDir);
        if (compiled.isEmpty()) {
            System.err.println("HotSwap: no compiled classes in " + classesDir);
            System.exit(1);
        }

        VirtualMachine vm;
        try {
            vm = attach(port);
        } catch (IOException e) {
            System.err.println("HotSwap: cannot connect to 127.0.0.1:" + port
                    + " (" + e.getMessage() + ").");
            System.err.println("HotSwap: start the IDE with ./scripts/dev-debug.sh first.");
            System.exit(1);
            return;
        }
        int redefined = 0;
        List<String> failures = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();

        for (ReferenceType type : vm.allClasses()) {
            String name = type.name();
            byte[] bytes = compiled.get(name);
            if (bytes == null) {
                continue;
            }
            if (!(type instanceof ClassType)) {
                continue;
            }
            try {
                vm.redefineClasses(Collections.singletonMap(type, bytes));
                compiled.remove(name);
                redefined++;
            } catch (Exception e) {
                failures.add(name + ": " + e.getMessage());
            }
        }
        notLoaded.addAll(compiled.keySet());

        System.out.println("HotSwap: " + redefined + " class(es) redefined on port " + port);
        if (!notLoaded.isEmpty()) {
            System.out.println("HotSwap: not loaded yet (ignored): " + String.join(", ", notLoaded));
        }
        for (String failure : failures) {
            System.out.println("HotSwap: could not redefine " + failure);
        }
        vm.dispose();

        if (redefined == 0) {
            System.out.println("HotSwap: nothing was redefined — was the IDE started with "
                    + "scripts/dev-debug.sh? Extension / plugin.xml changes still need a restart.");
            System.exit(1);
        }
    }

    private static Map<String, byte[]> readClasses(Path classesDir) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        if (!Files.isDirectory(classesDir)) {
            return result;
        }
        try (Stream<Path> files = Files.walk(classesDir)) {
            files.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String relative = classesDir.relativize(p).toString()
                                .replace('\\', '/').replace('/', '.');
                        String name = relative.substring(0, relative.length() - ".class".length());
                        if (name.equals("module-info") || name.endsWith("package-info")) {
                            return;
                        }
                        try {
                            result.put(name, Files.readAllBytes(p));
                        } catch (IOException e) {
                            System.err.println("HotSwap: cannot read " + p + ": " + e.getMessage());
                        }
                    });
        }
        return result;
    }

    private static VirtualMachine attach(int port)
            throws IOException, IllegalConnectorArgumentsException {
        AttachingConnector connector = null;
        for (AttachingConnector candidate : Bootstrap.virtualMachineManager()
                .attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(candidate.name())) {
                connector = candidate;
                break;
            }
        }
        if (connector == null) {
            throw new IllegalStateException("No JDWP socket attach connector available");
        }
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(String.valueOf(port));
        return connector.attach(arguments);
    }
}
