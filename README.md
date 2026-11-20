# DebugMock — Editor Quick Add Mock

IntelliJ IDEA plugin that lets you create, edit and manage Mock rules directly from a Java
method in the editor. Method information is obtained exclusively through the **Java PSI API**
(no regex / source-text parsing). Rules are enforced at runtime by a bundled **javaagent**
(ASM bytecode instrumentation), so they actually change method behaviour — not just add a
debugger breakpoint.

## Features

- **Right-click a Java method → `DebugMock → Add Mock Rule`**, or click the gutter icon next to
  the method declaration.
- **Line-level rules**: right-click inside a method body → **`Add DebugMock Rule at This Line`**
  (also available as `DebugMock → Add Rule at This Line`). The rule runs on *that exact source
  line* — not at method entry. The caret line is normalised to the enclosing statement start, so
  blank lines / continuation lines still target a real statement.
- **A line rule is anchored to its code, not to a raw line number.** Every rule stores the
  whitespace-normalised text of its statement (and its ordinal inside the method). When a line
  above the rule is inserted or deleted, the rule moves with its statement — just like a debugger
  breakpoint — instead of silently jumping to whatever now sits on the old line number. The IDE
  relocates the rule on every PSI change and writes the corrected line back to `rules.json`; the
  gutter, tool window, `Go to Source` and the editor dialog all resolve the anchor on the fly. The
  javaagent also falls back to the statement ordinal when the running class was compiled before
  the edit, so a stale `rules.json` still targets the same statement.
- The gutter only shows a line icon for lines that **already have a rule** (no icon on every
  statement). Click it to edit / enable / disable / delete the rules on that line, or to add
  another one. Existing rules show `@line N` in the tool window.
- Works both when the caret is **inside** a method and when a **method declaration is selected**.
- Automatically derives: fully-qualified class name, method name, JVM method descriptor
  (with generic erasure, arrays, nested classes and overloads), return type and parameter types.
- Unified dialog: **Condition** is a Java code editor placed above; **Value / Code** is one Java
  editor whose placeholder changes with the Action (control itself never changes). All fields
  share a fixed width, are single-line by default and expand to a text area via the arrow button
  (multi-line content, a pasted block or the JSON skeleton expand automatically). The expanded
  editor has its own scrollbar, so a long block scrolls instead of stretching the dialog.
  **Enter never submits the dialog** while the caret is in the editor: the plain Enter key is
  intercepted before the dialog's default button, so `Create` / `Save` only fires from the button
  (or an explicit action). When the editor is expanded, Enter still inserts a normal line break.
  "Add Execute" in the context menu adds a line-level rule using the caret line automatically.
- `RETURN` on an object return type has a **`JSON`** button: one click inserts a JSON skeleton
  inferred from the return type's PSI fields (nested objects, collections, enums, records, and the
  real type arguments of generic classes such as `Page<User>`), so you only fill in the values.
  On save the JSON is turned into Java that builds the object graph (`new demo.User()`,
  `setId(1)`, `List`/`Set`/`Map`/array/enum handling). JDK value types are supported directly
  (`LocalDate`/`LocalDateTime`/`Instant`, `Duration`, `UUID`, `BigDecimal`, `Date`, `URI`,
  `Optional<…>` …), and plain beans may have either setters or a matching constructor.
  When a shape really cannot be built (no setter, no suitable constructor, abstract type) the
  dialog says so instead of saving code that does not compile. The JSON is only an authoring
  format: the editor highlights it with the Java annotator, so its red marks are not errors — the
  hint under the field says *JSON is turned into Java on save*.
- The dialog also carries the rule's state: an **`Enabled`** checkbox, and a **`Delete`** button
  that appears **only when editing an existing rule** (a new rule has nothing to delete).
- Lightweight configuration dialog with a type-aware value editor:
  - `void` → defaults to `SKIP`
  - `THROW` → the thrown exception as Java code
  - `SKIP` → only offered for void methods
  - **EXECUTE** action: run a **real Java code block** automatically at the target method. The IDE
    compiles the block with the project's full classpath (so `if`/`for`/local variables/imports/
    any project class work) to validate it, and stores the **full generated Java source** in
    rules.json. The agent recompiles that source **in the application JVM** with its own JDK, so
    the bytecode always matches the runtime — an IDE on JDK 25 and an app on JDK 22 is fine.
    On a JRE without `jdk.compiler`, it falls back to the bytecode saved by the IDE (compiled at
    the lowest bytecode level javac accepts). `return <value>;` returns from the method and `throw new Exception(...);`
    throws — both stop the original body; plain statements let it continue. The **Line** field
    (pre-filled with the caret line) selects where inside the method it runs — `0` means method
    entry, a positive number is an exact source line. Compilation errors are shown and block saving.
  - **Condition** field on every rule for conditional execution (see below).
- **Multiple conditional rules per method**: one method can have several rules, each with its own
  condition; they are evaluated in order and the first match wins.
  - The **DebugMock tool window** shows a two-level tree (method → rules); the toolbar also works on
    a method row, where Enable/Disable and Delete apply to every rule of that method. Rule actions live on a
    **toolbar above the list** — Edit, Go to Source, Enable/Disable, Delete — and operate on the
    selected rule (method rows get Add Rule / Enable All / Disable All / Delete All from the
    right-click menu). Every rule starts with a **state dot — green when enabled, grey when disabled** — while the text
    keeps the normal theme colour, and the list itself uses the tool window background (no grey
    panel behind it). Clicking a rule only selects it — **`Go to Source`** (toolbar or context menu) jumps to its line,
    so a click never moves the editor. Double-click opens the rule for editing.
  - The toolbar's right side has a labelled **master switch** — a checkbox `All rules: on / off /
    mixed` — that enables or disables every rule of the project at once. It is deliberately a
    checkbox with text, so it can never be confused with the per-rule Enable/Disable icon.
  - Clicking the **gutter icon** (method or line) opens one **flat** menu — Edit, Enable/Disable,
    Delete for each rule right there (no submenu), plus "Add Mock Rule…" / "Add Rule at Line N…".
    With several rules the items are suffixed with the rule they belong to.
- On Create:
  1. saves the rule to `<project>/.debugmock/rules.json`,
  2. refreshes the DebugMock tool window,
  3. registers a method-entry breakpoint if a debug session is active; on the next Debug start
     (local **or** remote) all enabled rules get their breakpoints registered automatically,
  4. shows a success notification,
  5. asks before overwriting an existing rule for the same class + method + signature.

## Runtime enforcement (javaagent)

`src/agent/java` builds `debugmock-agent.jar`, which is bundled inside the plugin (compiled for
Java 8, so it works on JDK 8+). The agent reads `.debugmock/rules.json` and inserts conditional
checks with ASM: rules with `lineNumber: 0` run at **method entry**, rules with a positive
`lineNumber` run **on that source line**, right at the statement start (after the line number and
stack-map frame). A matching `RETURN` / `THROW` / `SKIP` ends the method at that point; an EXECUTE
block that falls through returns an internal `CONTINUE` sentinel, so the original body continues
from that line. It also watches the rules file, so editing a rule while the JVM is running takes
effect immediately.

### Condition syntax

```
args[0] == 1                          numeric argument
args[0].name == "Ann"                 bean property / getter on argument
args[0].getId() > 10                  method call on argument
args.length == 2                       argument count
threadName == "main"                   current thread name
args[0] > 1 && args[1] != null         boolean composition
```

Literals: numbers, `'...'` / `"..."` strings, `true`, `false`, `null`. Operators: `== != > < >= <=
&& || !`, plus `+` for numeric addition / string concatenation. Available variables:
- `args` (`Object[]`) and `args.length`
- `threadName` (`String`), `now` (`long`), `System`
- **every method parameter by its real name** — e.g. `id == 7`, `user.name == "Ann"` (the rule
  stores the names in `parameterNames` so the agent can bind them)
- `self` (alias `this`) — the receiver for instance methods

Leave the condition empty to always apply the rule. Example: `x < 100 && self.isEnabled()`.

### EXECUTE blocks

The Statement is a real Java method body compiled by the IDE. The generated `__execute` method
declares the same variables code completion offers in the dialog: `args` (`Object[]`),
`threadName` (`String`), `now` (`long`), `self` (the receiver, instance methods only) and **every
method parameter with its real name and erased source type**. If a parameter is itself called
`args` / `self` / `now` / `threadName`, the injected helper gets a trailing underscore so it never
shadows the parameter.

```java
// side effects — original body continues
System.out.println("hit " + args[0]);
((User) args[0]).setName("x");

// full Java syntax works
if (((Number) args[0]).intValue() > 10) {
    int sum = 0;
    for (int i = 0; i < 3; i++) sum += i;
    return "big-" + sum;          // return: original body skipped
}
throw new IllegalStateException("boom");  // throw: original body skipped
```

```java
// method parameters and self are available directly — no casts via args needed
System.out.println("user=" + name + ", receiver=" + self);
if (age > 18) return "adult";
```

`import` lines at the top of the block are supported. `return` / `throw` stop the original method
body; otherwise it continues. The block runs at the rule's **Line** (method entry when `0`), only
when the condition matches. Compiled bytecode lives with the rule in rules.json.

### The code editor

The Condition and Value / Code fields behave like the debugger's **Evaluate Expression** editor:
they are backed by a real Java code fragment whose context is the target method body, so

- Java syntax highlighting and semantic (variable / field / method) colouring follow the IDE scheme;
- completion pops up **automatically while typing** — no `Ctrl+Space` needed — and offers the
  method parameters by name (`name : String`, `age : int`), the **local variables in scope** at the
  rule's line (`sum : int`, `product : int`), `self`, `args`, `threadName`, `now` and every visible
  class;
- the target file's imports plus common JDK packages (`java.util.*`, `java.io.*`, `java.time.*`,
  …) are already in scope, so `List` / `ArrayList` / `LocalDate` compile without typing `import`.

For **line rules** the block also receives the real local variables of that line: the IDE reads
their names/types from PSI, declares them in the compiled block and the agent passes their live
values at the instrumentation point, so `System.out.println(sum + " (" + label + ")")` prints the
actual values instead of failing with `cannot find symbol`.

`compiledTerminates` in the rule records whether the block always returns / throws (the IDE detects
unreachable trailing code). `RETURN` and `THROW` rules always set it to `true`; an `EXECUTE` block
that falls through sets it to `false`, and the agent will continue the original body at that line.

A `RETURN` value that looks like JSON (`{ ... }` / `[ ... ]`) is compiled as a description of the
returned object when the method returns an object type: the JSON is converted to Java that builds
the object graph (`new demo.User()`, setters, `List`/`Set`/`Map`, arrays, enums, records). Field
names are the Java field names, and a plain JSON string / number / boolean is still returned as the
corresponding value.

- **Local Run / Debug**: the plugin automatically appends `-javaagent:<jar> -Ddebugmock.rules=<path>`
  to the JVM options of any Java run configuration in a project that has rules.
- **Remote JVM**: use `DebugMock → Copy Agent JVM Options (Remote JVM)` in the editor context
  menu, copy the printed jar and rules.json to the remote machine, and add those options to the
  remote JVM command line.
- On methods that already have a rule, the context menu offers **Edit / Enable-Disable / Remove**.

## Development loop

Normal Java changes need **no reinstall and no IDE restart**:

```bash
./scripts/dev-debug.sh      # once: starts the sandbox IDE in debug mode (JDWP on 5005)
# ... edit Java ...
./gradlew hotSwap           # recompiles and redefines the loaded plugin classes
```

`hotSwap` attaches to the running IDE over JDWP (`src/devTools/java/.../HotSwap.java`) and calls
`VirtualMachine.redefineClasses` with the freshly compiled bytecode. The dev IDE runs with JBR's
`-XX:+AllowEnhancedClassRedefinition`, so added methods / fields are accepted too; the task prints
how many classes were redefined and which ones are not loaded yet. Classes that were never used
since the IDE started cannot be redefined yet — open the feature once and run `hotSwap` again. Use
`-PdebugPort=5006` when the IDE listens elsewhere.

What still needs `./scripts/restart-ide.sh` (rebuild + background restart, log in
`build/runIde.log`): anything in `plugin.xml` — new actions, tool window, extension points,
listeners. The platform cannot register or unregister extensions at runtime, and unloading the
whole plugin dynamically is refused for these extension points (`idea.auto.reload.plugins` only
works for plugins whose extensions are all unloadable), so a restart is the honest fallback for
those changes.

## Build

Requires JDK 25 to build the plugin (see `build.gradle` toolchain). This is independent of the JVM
that runs the mocked application: EXECUTE rules carry their Java source and are compiled by the
application's own JDK at runtime, so the bytecode version always matches the app (IDE 25 + app 22
is fine). The IDE's precompiled bytecode is kept as a fallback for runtimes without a compiler.

```bash
./gradlew buildPlugin      # → build/distributions/idea-debug-mocker-1.0.0.zip
./gradlew runIde           # sandbox IDE with the plugin installed
./gradlew test             # tests
```

Targets IntelliJ IDEA 2026.3 (`since-build=263`); depends on `com.intellij.java` for Java PSI.

## Layout

```
src/main/java/com/debugmock/
├── actions/       Add / Edit / Toggle / Remove / Copy-agent-options AnActions
├── agent/         locates and extracts the bundled javaagent jar
├── debug/         method-entry breakpoints + re-registration on Debug session start
├── gutter/        method + statement (line) gutter icon LineMarkerProviders
├── model/         MockAction, MockRule
├── psi/           MethodResolver, MethodInfo, JvmDescriptorUtil,
│                  ExecutionContext (completion/compiled-block variables),
│                  LineTargets (caret → statement start),
│                  RuleLines (line anchor ↔ statement relocation)
├── run/            RunConfigurationExtension: injects -javaagent into local Run/Debug
├── store/         MockRuleStore (.debugmock/rules.json) + change listener,
│                  MockRuleLineSync (moves line rules when the source is edited)
├── toolwindow/    rules tool window with auto-refresh
├── ui/            configuration dialog + shared create/edit flow
└── util/          notifications
src/agent/java/com/debugmock/agent/
├── MockAgent.java        premain + hot-reload watcher
├── MockTransformer.java  ASM tree instrumentation (entry + line rules, frame recomputation)
├── MockValues.java       JSON -> object/array conversion
└── RuleLoader.java       rules.json parser
```
