package com.debugmock.psi;

import com.debugmock.model.MockRule;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds a line rule to the <em>statement</em> it was created on, not to a raw line number.
 *
 * <p>A stored line number breaks as soon as a line above it is added or removed. To make line
 * rules behave like debugger breakpoints, every rule also keeps:</p>
 * <ul>
 *   <li>a <b>line anchor</b> — the whitespace-normalised text of the target statement;</li>
 *   <li>a <b>line ordinal</b> — the index of that statement among the method's statement starts.</li>
 * </ul>
 *
 * <p>{@link #resolve(MockRule, PsiMethod)} returns the line the statement lives on <em>now</em>:
 * first the stored line if its text still matches, then an exact anchor match elsewhere in the
 * method, then a fuzzy (edited) anchor match, then the ordinal. This keeps the rule on the same
 * code when rows above are inserted or deleted.</p>
 */
public final class RuleLines {

    private static final int ANCHOR_LIMIT = 512;

    private RuleLines() {
    }

    /** One executable statement start inside a method body. */
    public static final class StatementLine {
        public final int line;
        public final String anchor;

        StatementLine(int line, String anchor) {
            this.line = line;
            this.anchor = anchor;
        }
    }

    /** Whitespace-normalised statement text used as the code anchor. */
    public static String normalize(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > ANCHOR_LIMIT ? oneLine.substring(0, ANCHOR_LIMIT) : oneLine;
    }

    /** Anchor text of the statement that starts on the given 1-based line, or {@code null}. */
    @Nullable
    public static String anchorFor(@Nullable PsiMethod method, int oneBasedLine) {
        StatementLine statement = statementAt(method, oneBasedLine);
        return statement == null ? null : statement.anchor;
    }

    /** Ordinal of the statement start line inside the method, or {@code -1}. */
    public static int ordinalFor(@Nullable PsiMethod method, int oneBasedLine) {
        List<StatementLine> statements = statements(method);
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).line == oneBasedLine) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Resolves the current source line of the statement a rule was attached to.
     *
     * @return the resolved 1-based line, or the stored line when the method has no usable
     *         statements (entry rules keep {@code 0}).
     */
    public static int resolve(@Nullable MockRule rule, @Nullable PsiMethod method) {
        if (rule == null) {
            return 0;
        }
        if (rule.getLineNumber() <= 0 || method == null || method.getBody() == null) {
            return rule.getLineNumber();
        }
        PsiFile file = method.getContainingFile();
        Document document = file == null ? null
                : PsiDocumentManager.getInstance(method.getProject()).getDocument(file);
        if (document == null) {
            return rule.getLineNumber();
        }
        return resolve(method, document, rule.getLineNumber(), rule.getLineAnchor(), rule.getLineOrdinal());
    }

    public static int resolve(@Nullable PsiMethod method, @Nullable Document document,
                              int storedLine, @Nullable String anchor, int ordinal) {
        if (storedLine <= 0 || method == null || document == null) {
            return storedLine;
        }
        List<StatementLine> statements = statements(method);
        if (statements.isEmpty()) {
            return storedLine;
        }

        // 1. The stored line still carries the same statement: nothing moved.
        StatementLine atStoredLine = null;
        for (StatementLine statement : statements) {
            if (statement.line == storedLine) {
                atStoredLine = statement;
                break;
            }
        }
        if (atStoredLine != null && (anchor == null || anchor.isEmpty()
                || matches(atStoredLine.anchor, anchor))) {
            return storedLine;
        }

        // 2. The statement text moved: follow it exactly.
        if (anchor != null && !anchor.isEmpty()) {
            List<StatementLine> exact = new ArrayList<>();
            for (StatementLine statement : statements) {
                if (matches(statement.anchor, anchor)) {
                    exact.add(statement);
                }
            }
            if (exact.size() == 1) {
                return exact.get(0).line;
            }
            if (exact.size() > 1) {
                return closest(exact, storedLine).line;
            }

            // 3. The statement itself was edited: pick the most similar statement.
            StatementLine best = null;
            double bestScore = 0;
            for (StatementLine statement : statements) {
                double score = similarity(statement.anchor, anchor);
                if (score > bestScore) {
                    bestScore = score;
                    best = statement;
                }
            }
            if (best != null && bestScore >= 0.6) {
                return best.line;
            }
        }

        // 4. Legacy rules without an anchor fall back to the same ordinal statement.
        if ((anchor == null || anchor.isEmpty()) && ordinal >= 0 && ordinal < statements.size()) {
            return statements.get(ordinal).line;
        }
        return storedLine;
    }

    /** Statement starts of a method body, in source order, one per source line. */
    public static List<StatementLine> statements(@Nullable PsiMethod method) {
        List<StatementLine> result = new ArrayList<>();
        if (method == null || method.getBody() == null) {
            return result;
        }
        PsiFile file = method.getContainingFile();
        Document document = file == null ? null
                : PsiDocumentManager.getInstance(method.getProject()).getDocument(file);
        if (document == null) {
            return result;
        }
        List<PsiStatement> ordered = new ArrayList<>(
                PsiTreeUtil.findChildrenOfType(method.getBody(), PsiStatement.class));
        ordered.sort(Comparator.comparingInt(statement -> statement.getTextRange().getStartOffset()));

        for (PsiStatement statement : ordered) {
            if (statement instanceof PsiCodeBlock) {
                continue;
            }
            int offset = statement.getTextRange().getStartOffset();
            if (offset < 0 || offset > document.getTextLength()) {
                continue;
            }
            int line = document.getLineNumber(offset) + 1;
            boolean seen = false;
            for (StatementLine existing : result) {
                if (existing.line == line) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                result.add(new StatementLine(line, normalize(statement.getText())));
            }
        }
        return result;
    }

    @Nullable
    private static StatementLine statementAt(@Nullable PsiMethod method, int line) {
        for (StatementLine statement : statements(method)) {
            if (statement.line == line) {
                return statement;
            }
        }
        return null;
    }

    private static StatementLine closest(List<StatementLine> candidates, int line) {
        StatementLine best = candidates.get(0);
        int bestDistance = Math.abs(best.line - line);
        for (StatementLine candidate : candidates) {
            int distance = Math.abs(candidate.line - line);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** True when two anchors denote the same statement (allowing the truncation prefix). */
    private static boolean matches(String current, String stored) {
        if (current.equals(stored)) {
            return true;
        }
        // Anchors are capped, so one may be a prefix of the other.
        return current.length() >= ANCHOR_LIMIT || stored.length() >= ANCHOR_LIMIT
                ? current.startsWith(stored) || stored.startsWith(current)
                : false;
    }

    /** Dice coefficient over character bigrams, cheap enough for the edit fallback. */
    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b) ? 1 : 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        if (a.length() < 2 || b.length() < 2) {
            return 0;
        }
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i < a.length() - 1; i++) {
            grams.merge(a.substring(i, i + 2), 1, Integer::sum);
        }
        int overlap = 0;
        for (int i = 0; i < b.length() - 1; i++) {
            String gram = b.substring(i, i + 2);
            Integer count = grams.get(gram);
            if (count != null && count > 0) {
                grams.put(gram, count - 1);
                overlap++;
            }
        }
        return 2.0 * overlap / (a.length() - 1 + b.length() - 1);
    }
}
