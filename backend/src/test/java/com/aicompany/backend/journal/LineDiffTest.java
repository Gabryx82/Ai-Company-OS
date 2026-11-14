package com.aicompany.backend.journal;

import com.aicompany.backend.journal.LineDiff.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** PHASE 37 (ADR-047): what changed in a file, line by line, in hunks with three lines of context. */
class LineDiffTest {

    private static List<String> lines(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> "riga " + i).toList();
    }

    private static String render(LineDiff.DiffResult result) {
        StringBuilder out = new StringBuilder();
        for (LineDiff.DiffHunk h : result.hunks()) {
            out.append("@@ -").append(h.oldStart()).append(',').append(h.oldLines())
                    .append(" +").append(h.newStart()).append(',').append(h.newLines()).append(" @@\n");
            for (LineDiff.DiffLine l : h.lines()) {
                out.append(l.kind() == Kind.ADDED ? '+' : l.kind() == Kind.REMOVED ? '-' : ' ').append(l.text()).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void theSameFileHasNoChange() {
        LineDiff.DiffResult result = LineDiff.diff(lines(1, 20), lines(1, 20));
        assertThat(result.additions()).isZero();
        assertThat(result.deletions()).isZero();
        assertThat(result.hunks()).isEmpty();
    }

    @Test
    void aChangedLineShowsWhatWentAndWhatCameWithThreeLinesAround() {
        List<String> after = new ArrayList<>(lines(1, 20));
        after.set(9, "riga 10, corretta");
        after.add(10, "riga nuova");
        LineDiff.DiffResult result = LineDiff.diff(lines(1, 20), after);

        assertThat(result.additions()).isEqualTo(2);
        assertThat(result.deletions()).isEqualTo(1);
        assertThat(render(result)).isEqualTo("""
                @@ -7,7 +7,8 @@
                 riga 7
                 riga 8
                 riga 9
                -riga 10
                +riga 10, corretta
                +riga nuova
                 riga 11
                 riga 12
                 riga 13
                """);
        LineDiff.DiffLine added = result.hunks().getFirst().lines().get(5);
        assertThat(added.oldNumber()).isNull();
        assertThat(added.newNumber()).isEqualTo(11);
        LineDiff.DiffLine context = result.hunks().getFirst().lines().get(6);
        assertThat(context.oldNumber()).isEqualTo(11);
        assertThat(context.newNumber()).isEqualTo(12);
    }

    @Test
    void changesFarApartAreTwoHunksAndChangesCloseTogetherAreOne() {
        List<String> after = new ArrayList<>(lines(1, 40));
        after.remove(2);
        after.set(33, "riga 35 bis");
        LineDiff.DiffResult far = LineDiff.diff(lines(1, 40), after);
        assertThat(far.hunks()).hasSize(2);
        assertThat(far.hunks().get(1).oldStart()).isEqualTo(32);

        List<String> close = new ArrayList<>(lines(1, 40));
        close.set(9, "dieci");
        close.set(14, "quindici");
        assertThat(LineDiff.diff(lines(1, 40), close).hunks()).hasSize(1);
    }

    @Test
    void aNewFileIsAllAddedAndARemovedOneAllRemoved() {
        LineDiff.DiffResult created = LineDiff.diff(List.of(), lines(1, 4));
        assertThat(created.additions()).isEqualTo(4);
        assertThat(render(created)).isEqualTo("@@ -0,0 +1,4 @@\n+riga 1\n+riga 2\n+riga 3\n+riga 4\n");
        LineDiff.DiffResult gone = LineDiff.diff(lines(1, 2), List.of());
        assertThat(gone.deletions()).isEqualTo(2);
        assertThat(render(gone)).isEqualTo("@@ -1,2 +0,0 @@\n-riga 1\n-riga 2\n");
    }

    @Test
    void twoFilesWithNothingInCommonStillCountRight() {
        List<String> before = IntStream.range(0, 3000).mapToObj(i -> "a" + i).toList();
        List<String> after = IntStream.range(0, 2500).mapToObj(i -> "b" + i).toList();
        LineDiff.DiffResult result = LineDiff.diff(before, after);
        assertThat(result.deletions()).isEqualTo(3000);
        assertThat(result.additions()).isEqualTo(2500);
    }
}
