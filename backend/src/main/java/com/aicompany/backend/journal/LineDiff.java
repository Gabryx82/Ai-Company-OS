package com.aicompany.backend.journal;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 37 (ADR-047): what changed in a file, line by line -- the shortest edit script
 * (Myers' O(ND) algorithm) after the lines the two sides share at both ends, shown as
 * hunks with three lines of context, as a unified diff shows it. Two files with almost
 * nothing in common are not searched for long: everything between the shared ends is
 * removed and added, which is what they are.
 */
public final class LineDiff {

    /** The lines shown around a change. */
    public static final int CONTEXT = 3;
    /** Beyond this many edits the search stops; memory grows with its square. */
    private static final int MAX_EDITS = 2000;

    private static final byte EQUAL = 0;
    private static final byte DELETE = 1;
    private static final byte INSERT = 2;

    private LineDiff() {
    }

    public enum Kind { CONTEXT, ADDED, REMOVED }

    /** One line of a hunk; its number in the file before ({@code oldNumber}) and after, where it is in it. */
    public record DiffLine(Kind kind, @Nullable Integer oldNumber, @Nullable Integer newNumber, String text) {
    }

    /** As a unified diff writes it: {@code @@ -oldStart,oldLines +newStart,newLines @@}. */
    public record DiffHunk(int oldStart, int oldLines, int newStart, int newLines, List<DiffLine> lines) {
    }

    public record DiffResult(int additions, int deletions, List<DiffHunk> hunks) {
    }

    public static DiffResult diff(List<String> before, List<String> after) {
        int n = before.size();
        int m = after.size();
        int prefix = 0;
        while (prefix < n && prefix < m && before.get(prefix).equals(after.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix && before.get(n - 1 - suffix).equals(after.get(m - 1 - suffix))) {
            suffix++;
        }
        List<String> a = before.subList(prefix, n - suffix);
        List<String> b = after.subList(prefix, m - suffix);
        byte[] middle = shortestScript(a, b);
        if (middle == null) {
            middle = new byte[a.size() + b.size()];
            java.util.Arrays.fill(middle, 0, a.size(), DELETE);
            java.util.Arrays.fill(middle, a.size(), middle.length, INSERT);
        }
        byte[] ops = new byte[prefix + middle.length + suffix];
        System.arraycopy(middle, 0, ops, prefix, middle.length);
        deletionsFirst(ops);

        List<DiffLine> all = new ArrayList<>(ops.length);
        int additions = 0;
        int deletions = 0;
        int oi = 0;
        int ni = 0;
        for (byte op : ops) {
            if (op == EQUAL) {
                all.add(new DiffLine(Kind.CONTEXT, oi + 1, ni + 1, after.get(ni)));
                oi++;
                ni++;
            } else if (op == DELETE) {
                all.add(new DiffLine(Kind.REMOVED, oi + 1, null, before.get(oi)));
                oi++;
                deletions++;
            } else {
                all.add(new DiffLine(Kind.ADDED, null, ni + 1, after.get(ni)));
                ni++;
                additions++;
            }
        }
        return new DiffResult(additions, deletions, hunks(all));
    }

    /** The edit script between two lists, or null when it would take more than {@link #MAX_EDITS} edits. */
    private static byte @Nullable [] shortestScript(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int max = n + m;
        if (max == 0) {
            return new byte[0];
        }
        int offset = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        search:
        for (int d = 0; d <= max; d++) {
            if (d > MAX_EDITS) {
                return null;
            }
            // The furthest x on each diagonal k in [-d-1, d+1] as the previous round left it.
            int[] snapshot = new int[2 * d + 3];
            System.arraycopy(v, offset - d - 1, snapshot, 0, snapshot.length);
            trace.add(snapshot);
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1]) ? v[offset + k + 1] : v[offset + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    found = d;
                    break search;
                }
            }
        }
        byte[] reversed = new byte[max];
        int length = 0;
        int x = n;
        int y = m;
        for (int d = found; d >= 0; d--) {
            int[] snapshot = trace.get(d);
            int base = d + 1;
            int k = x - y;
            int previousK = k == -d || (k != d && snapshot[k - 1 + base] < snapshot[k + 1 + base]) ? k + 1 : k - 1;
            int previousX = snapshot[previousK + base];
            int previousY = previousX - previousK;
            while (x > previousX && y > previousY) {
                reversed[length++] = EQUAL;
                x--;
                y--;
            }
            if (d > 0) {
                reversed[length++] = x == previousX ? INSERT : DELETE;
            }
            x = previousX;
            y = previousY;
        }
        byte[] script = new byte[length];
        for (int i = 0; i < length; i++) {
            script[i] = reversed[length - 1 - i];
        }
        return script;
    }

    /** In each run of edits, what went before what came: {@code -old +new}, as diffs read. */
    private static void deletionsFirst(byte[] ops) {
        int i = 0;
        while (i < ops.length) {
            if (ops[i] == EQUAL) {
                i++;
                continue;
            }
            int start = i;
            int deleted = 0;
            while (i < ops.length && ops[i] != EQUAL) {
                if (ops[i] == DELETE) {
                    deleted++;
                }
                i++;
            }
            for (int j = start; j < i; j++) {
                ops[j] = j - start < deleted ? DELETE : INSERT;
            }
        }
    }

    private static List<DiffHunk> hunks(List<DiffLine> all) {
        List<DiffHunk> hunks = new ArrayList<>();
        int i = 0;
        while (i < all.size()) {
            if (all.get(i).kind() == Kind.CONTEXT) {
                i++;
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int lastChange = i;
            int j = i + 1;
            // Two changes closer than twice the context share a hunk.
            while (j < all.size()) {
                if (all.get(j).kind() != Kind.CONTEXT) {
                    lastChange = j;
                } else if (j - lastChange > 2 * CONTEXT) {
                    break;
                }
                j++;
            }
            int end = Math.min(all.size(), lastChange + CONTEXT + 1);
            hunks.add(hunk(all, start, end));
            i = end;
        }
        return hunks;
    }

    private static DiffHunk hunk(List<DiffLine> all, int start, int end) {
        int oldBefore = 0;
        int newBefore = 0;
        for (int i = 0; i < start; i++) {
            if (all.get(i).oldNumber() != null) {
                oldBefore++;
            }
            if (all.get(i).newNumber() != null) {
                newBefore++;
            }
        }
        List<DiffLine> lines = List.copyOf(all.subList(start, end));
        int oldLines = (int) lines.stream().filter(l -> l.oldNumber() != null).count();
        int newLines = (int) lines.stream().filter(l -> l.newNumber() != null).count();
        return new DiffHunk(oldLines == 0 ? oldBefore : oldBefore + 1, oldLines, newLines == 0 ? newBefore : newBefore + 1, newLines, lines);
    }
}
