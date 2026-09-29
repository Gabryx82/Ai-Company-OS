package com.aicompany.backend.blueprint.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The automatic layout of a Blueprint (ADR-033 §6), computed by the server so it
 * is the same for every client and a test can pin it.
 *
 * <p>Columns by architectural tier, left to right in the direction of the
 * dependencies -- outputs on the right of a block, inputs on its left, so most
 * edges run rightwards. Inside a column blocks start grouped by folder and name,
 * then two barycentre sweeps (left to right, right to left) move each block
 * towards the blocks it is connected to, which is what keeps crossings down. A
 * tall column wraps into sub-columns of at most {@link #MAX_ROWS} blocks, so a
 * project with two hundred classes is wide rather than twenty metres tall.
 */
public final class BlueprintLayoutEngine {

    public static final int COLUMN_WIDTH = 340;
    public static final int ROW_HEIGHT = 170;
    public static final int MAX_ROWS = 18;

    /** A block to place: its tier column and what orders it before the sweeps. */
    public record Item(long id, int column, String group) {
    }

    public record Point(double x, double y) {
    }

    private BlueprintLayoutEngine() {
    }

    public static Map<Long, Point> layout(List<Item> items, List<long[]> edges) {
        Map<Integer, List<Item>> columns = new TreeMap<>();
        items.stream().sorted(Comparator.comparing(Item::group).thenComparingLong(Item::id))
                .forEach(i -> columns.computeIfAbsent(i.column(), c -> new ArrayList<>()).add(i));
        Map<Long, List<Long>> neighbours = new HashMap<>();
        for (long[] e : edges) {
            neighbours.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
            neighbours.computeIfAbsent(e[1], k -> new ArrayList<>()).add(e[0]);
        }
        Map<Long, Double> rank = new HashMap<>();
        columns.values().forEach(col -> {
            for (int i = 0; i < col.size(); i++) {
                rank.put(col.get(i).id(), (double) i / Math.max(1, col.size() - 1));
            }
        });
        List<Integer> order = new ArrayList<>(columns.keySet());
        for (int sweep = 0; sweep < 4; sweep++) {
            List<Integer> pass = new ArrayList<>(order);
            if (sweep % 2 == 1) {
                java.util.Collections.reverse(pass);
            }
            for (Integer c : pass) {
                List<Item> col = columns.get(c);
                Map<Long, Double> barycentre = new HashMap<>();
                for (Item i : col) {
                    List<Long> ns = neighbours.getOrDefault(i.id(), List.of());
                    double own = rank.get(i.id());
                    barycentre.put(i.id(), ns.isEmpty() ? own
                            : (ns.stream().mapToDouble(n -> rank.getOrDefault(n, own)).sum() + own) / (ns.size() + 1));
                }
                col.sort(Comparator.comparingDouble((Item i) -> barycentre.get(i.id())).thenComparing(Item::group)
                        .thenComparingLong(Item::id));
                for (int i = 0; i < col.size(); i++) {
                    rank.put(col.get(i).id(), (double) i / Math.max(1, col.size() - 1));
                }
            }
        }
        Map<Long, Point> out = new LinkedHashMap<>();
        int x = 0;
        int tallest = columns.values().stream().mapToInt(c -> Math.min(c.size(), MAX_ROWS)).max().orElse(0);
        for (List<Item> col : columns.values()) {
            int subColumns = (col.size() + MAX_ROWS - 1) / MAX_ROWS;
            int rows = Math.min(col.size(), MAX_ROWS);
            // centre short columns against the tallest one
            double offset = (tallest - rows) * ROW_HEIGHT / 2.0;
            for (int i = 0; i < col.size(); i++) {
                int sub = i / MAX_ROWS;
                int row = i % MAX_ROWS;
                out.put(col.get(i).id(), new Point(x + sub * COLUMN_WIDTH, offset + row * ROW_HEIGHT));
            }
            x += subColumns * COLUMN_WIDTH + 60;
        }
        return out;
    }
}
