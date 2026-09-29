package com.aicompany.backend.blueprint.layout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** PHASE 28 (ADR-033 §6): the server's layout -- deterministic, by tier, never stacking two blocks. */
class BlueprintLayoutEngineTest {

    @Test
    void tiersRunLeftToRightInTheDirectionOfTheDependencies() {
        Map<Long, BlueprintLayoutEngine.Point> p = BlueprintLayoutEngine.layout(List.of(
                new BlueprintLayoutEngine.Item(1, 4, "controller"), new BlueprintLayoutEngine.Item(2, 5, "service"),
                new BlueprintLayoutEngine.Item(3, 6, "repository"), new BlueprintLayoutEngine.Item(4, 8, "table")),
                List.of(new long[]{1, 2}, new long[]{2, 3}, new long[]{3, 4}));
        assertThat(p.get(1L).x()).isLessThan(p.get(2L).x());
        assertThat(p.get(2L).x()).isLessThan(p.get(3L).x());
        assertThat(p.get(3L).x()).isLessThan(p.get(4L).x());
    }

    @Test
    void theSameInputGivesTheSameLayout() {
        List<BlueprintLayoutEngine.Item> items = new ArrayList<>();
        List<long[]> edges = new ArrayList<>();
        for (long i = 1; i <= 60; i++) {
            items.add(new BlueprintLayoutEngine.Item(i, (int) (i % 7), "g" + (i % 5)));
            if (i > 1) edges.add(new long[]{i - 1, i});
        }
        assertThat(BlueprintLayoutEngine.layout(items, edges)).isEqualTo(BlueprintLayoutEngine.layout(items, edges));
    }

    @Test
    void aLargeBlueprintIsNeitherARibbonNorATowerAndNoTwoBlocksShareACell() {
        List<BlueprintLayoutEngine.Item> items = new ArrayList<>();
        for (long i = 1; i <= 600; i++) {
            items.add(new BlueprintLayoutEngine.Item(i, (int) (i % 10), "g"));
        }
        Map<Long, BlueprintLayoutEngine.Point> p = BlueprintLayoutEngine.layout(items, List.of());
        Set<String> cells = new HashSet<>();
        p.values().forEach(point -> assertThat(cells.add(point.x() + ":" + point.y())).as("two blocks at " + point).isTrue());
        double width = p.values().stream().mapToDouble(BlueprintLayoutEngine.Point::x).max().orElseThrow() + BlueprintLayoutEngine.COLUMN_WIDTH;
        double height = p.values().stream().mapToDouble(BlueprintLayoutEngine.Point::y).max().orElseThrow() + BlueprintLayoutEngine.ROW_HEIGHT;
        assertThat(width / height).isBetween(0.8, 3.0);
        assertThat(BlueprintLayoutEngine.rowsFor(10)).isEqualTo(BlueprintLayoutEngine.MIN_ROWS);
        assertThat(BlueprintLayoutEngine.rowsFor(100_000)).isEqualTo(BlueprintLayoutEngine.MAX_ROWS);
    }
}
