package com.aicompany.backend.blueprint.service;

import java.util.ArrayList;
import java.util.List;

/**
 * A unified diff between two texts, for people to read (ADR-033 §7): the format
 * {@code git diff} prints, three lines of context. Longest common subsequence on
 * lines -- quadratic, so a pair of files whose product of lines is too large is
 * shown as a whole replacement, which is still correct, only less precise.
 */
final class UnifiedDiff {

    private static final int CONTEXT = 3;
    private static final long MAX_CELLS = 4_000_000L;

    private UnifiedDiff() {
    }

    /** A file that does not exist yet: every line added. */
    static String creation(String path, String content) {
        List<String> lines = content.lines().toList();
        StringBuilder out = new StringBuilder();
        out.append("--- /dev/null\n+++ b/").append(path).append('\n');
        out.append("@@ -0,0 +1,").append(lines.size()).append(" @@\n");
        lines.forEach(l -> out.append('+').append(l).append('\n'));
        return out.toString();
    }

    static String between(String path, String before, String after) {
        List<String> a = before.lines().toList();
        List<String> b = after.lines().toList();
        List<Op> script = script(a, b);
        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(path).append('\n').append("+++ b/").append(path).append('\n');
        List<Integer> changes = new ArrayList<>();
        for (int k = 0; k < script.size(); k++) {
            if (script.get(k).kind != ' ') {
                changes.add(k);
            }
        }
        int c = 0;
        while (c < changes.size()) {
            int first = changes.get(c);
            int last = first;
            // changes closer than two contexts apart share a hunk
            while (c + 1 < changes.size() && changes.get(c + 1) - last <= 2 * CONTEXT) {
                last = changes.get(++c);
            }
            c++;
            int start = Math.max(0, first - CONTEXT);
            int end = Math.min(script.size(), last + CONTEXT + 1);
            List<Op> hunk = script.subList(start, end);
            int aStart = hunk.get(0).a + 1;
            int bStart = hunk.get(0).b + 1;
            int aLen = (int) hunk.stream().filter(o -> o.kind != '+').count();
            int bLen = (int) hunk.stream().filter(o -> o.kind != '-').count();
            out.append("@@ -").append(aLen == 0 ? aStart - 1 : aStart).append(',').append(aLen)
                    .append(" +").append(bLen == 0 ? bStart - 1 : bStart).append(',').append(bLen).append(" @@\n");
            for (Op o : hunk) {
                out.append(o.kind).append(o.text).append('\n');
            }
        }
        return out.toString();
    }

    private record Op(char kind, String text, int a, int b) {
    }

    private static List<Op> script(List<String> a, List<String> b) {
        List<Op> out = new ArrayList<>();
        if ((long) a.size() * b.size() > MAX_CELLS) {
            for (int i = 0; i < a.size(); i++) out.add(new Op('-', a.get(i), i, 0));
            for (int j = 0; j < b.size(); j++) out.add(new Op('+', b.get(j), a.size(), j));
            return out;
        }
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                out.add(new Op(' ', a.get(i), i++, j++));
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Op('-', a.get(i), i++, j));
            } else {
                out.add(new Op('+', b.get(j), i, j++));
            }
        }
        while (i < a.size()) out.add(new Op('-', a.get(i), i++, j));
        while (j < b.size()) out.add(new Op('+', b.get(j), i, j++));
        return out;
    }
}
