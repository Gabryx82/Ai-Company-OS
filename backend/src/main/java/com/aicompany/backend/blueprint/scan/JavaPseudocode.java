package com.aicompany.backend.blueprint.scan;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Pseudocode from the AST of a method body (ADR-033 §5): the control flow a
 * person needs to read a method -- conditions, loops, rejections, calls, returns --
 * without the syntax. Deterministic, so it can be regenerated at every scan and a
 * test can pin it.
 *
 * <pre>
 * IF project.isArchived()
 *   REJECT ArchivedProjectIsImmutableException
 * LET task = new Task(title, description)
 * CALL tasks.save(task)
 * RETURN task
 * </pre>
 */
public final class JavaPseudocode {

    static final int MAX_LINES = 40;
    static final int MAX_EXPRESSION = 70;
    private static final int MAX_DEPTH = 6;

    private final List<String> lines = new ArrayList<>();

    private JavaPseudocode() {
    }

    /** The pseudocode of a body; empty for an abstract or interface method. */
    public static String of(BlockStmt body) {
        if (body == null) {
            return "";
        }
        JavaPseudocode p = new JavaPseudocode();
        p.block(body.getStatements(), 0);
        if (p.lines.size() > MAX_LINES) {
            List<String> kept = new ArrayList<>(p.lines.subList(0, MAX_LINES));
            kept.add("… (" + (p.lines.size() - MAX_LINES) + " righe in più)");
            return String.join("\n", kept);
        }
        return String.join("\n", p.lines);
    }

    private void block(List<Statement> statements, int depth) {
        for (Statement s : statements) {
            statement(s, depth);
        }
    }

    private void emit(int depth, String text) {
        lines.add("  ".repeat(depth) + text);
    }

    private void body(Statement s, int depth) {
        if (depth > MAX_DEPTH) {
            emit(depth, "…");
            return;
        }
        if (s instanceof BlockStmt b) {
            block(b.getStatements(), depth);
        } else {
            statement(s, depth);
        }
    }

    private void statement(Statement s, int depth) {
        if (s instanceof IfStmt i) {
            emit(depth, "IF " + shorten(i.getCondition()));
            body(i.getThenStmt(), depth + 1);
            i.getElseStmt().ifPresent(e -> {
                if (e instanceof IfStmt) {
                    lines.add("  ".repeat(depth) + "ELSE");
                    body(e, depth + 1);
                } else {
                    emit(depth, "ELSE");
                    body(e, depth + 1);
                }
            });
        } else if (s instanceof ForEachStmt f) {
            emit(depth, "FOR EACH " + f.getVariable().getVariables().get(0).getNameAsString() + " IN "
                    + shorten(f.getIterable()));
            body(f.getBody(), depth + 1);
        } else if (s instanceof ForStmt f) {
            emit(depth, "LOOP" + f.getCompare().map(c -> " WHILE " + shorten(c)).orElse(""));
            body(f.getBody(), depth + 1);
        } else if (s instanceof WhileStmt w) {
            emit(depth, "WHILE " + shorten(w.getCondition()));
            body(w.getBody(), depth + 1);
        } else if (s instanceof DoStmt d) {
            emit(depth, "REPEAT UNTIL NOT " + shorten(d.getCondition()));
            body(d.getBody(), depth + 1);
        } else if (s instanceof ThrowStmt t) {
            Expression e = t.getExpression();
            emit(depth, "REJECT " + (e instanceof ObjectCreationExpr o ? o.getType().getNameAsString() : shorten(e)));
        } else if (s instanceof ReturnStmt r) {
            emit(depth, "RETURN" + r.getExpression().map(e -> " " + shorten(e)).orElse(""));
        } else if (s instanceof TryStmt t) {
            emit(depth, "TRY");
            body(t.getTryBlock(), depth + 1);
            for (CatchClause c : t.getCatchClauses()) {
                emit(depth, "ON " + c.getParameter().getType().asString());
                body(c.getBody(), depth + 1);
            }
            t.getFinallyBlock().ifPresent(f -> {
                emit(depth, "FINALLY");
                body(f, depth + 1);
            });
        } else if (s instanceof SwitchStmt sw) {
            emit(depth, "SWITCH " + shorten(sw.getSelector()));
            for (SwitchEntry entry : sw.getEntries()) {
                emit(depth + 1, entry.getLabels().isEmpty() ? "DEFAULT"
                        : "CASE " + String.join(", ", entry.getLabels().stream().map(JavaPseudocode::shorten).toList()));
                block(entry.getStatements(), depth + 2);
            }
        } else if (s instanceof SynchronizedStmt sync) {
            emit(depth, "LOCK " + shorten(sync.getExpression()));
            body(sync.getBody(), depth + 1);
        } else if (s instanceof BlockStmt b) {
            block(b.getStatements(), depth);
        } else if (s instanceof ExpressionStmt e) {
            expression(e.getExpression(), depth);
        }
        // Other statements (labels, local classes, empty, assert, break, continue)
        // carry no meaning a reader of the flow needs.
    }

    private void expression(Expression e, int depth) {
        if (e instanceof VariableDeclarationExpr v) {
            for (VariableDeclarator d : v.getVariables()) {
                emit(depth, "LET " + d.getNameAsString() + d.getInitializer().map(i -> " = " + shorten(i)).orElse(""));
            }
        } else if (e instanceof AssignExpr a) {
            emit(depth, "SET " + shorten(a.getTarget()) + " = " + shorten(a.getValue()));
        } else if (e instanceof MethodCallExpr m) {
            emit(depth, "CALL " + shorten(m));
        } else if (e instanceof ObjectCreationExpr o) {
            emit(depth, "CREATE " + o.getType().getNameAsString());
        } else {
            emit(depth, "DO " + shorten(e));
        }
    }

    /** An expression on one line, cut when long: pseudocode is read, not compiled. */
    static String shorten(Node e) {
        String text = e.toString().replaceAll("\\s+", " ").strip();
        return text.length() > MAX_EXPRESSION ? text.substring(0, MAX_EXPRESSION - 1) + "…" : text;
    }
}
