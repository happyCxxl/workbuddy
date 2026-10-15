package com.workbuddy.worker.parser.pdf.detect;

import com.workbuddy.worker.parser.pdf.model.Token;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对齐图剪枝：把「行」当节点，两行落在同一列上即连通，保留最大连通块作为表格主体，
 * 其余行判为混入的正文（它们在列上独来独往，不与表格主体共享列）。行数相同时保留 token 更多的连通块。
 *
 * @author cxxl
 */
public final class TableAlignmentPruner {

    private TableAlignmentPruner() {
    }

    /**
     * 保留的行下标（升序）。
     *
     * @param rowTokens 行 × token
     * @param columns   已聚出的列
     * @return 最大连通块的行下标；没有行或没有列时返回全部行下标
     */
    public static List<Integer> keptRows(List<List<Token>> rowTokens, List<TableCandidateDetector.Column> columns) {
        int rowCount = rowTokens.size();
        if (rowCount == 0 || columns.isEmpty()) {
            return allRows(rowCount);
        }
        List<Set<Integer>> rowColumns = new ArrayList<>();
        for (List<Token> row : rowTokens) {
            rowColumns.add(columnsOf(row, columns));
        }
        int[] parent = new int[rowCount];
        for (int index = 0; index < rowCount; index++) {
            parent[index] = index;
        }
        for (int left = 0; left < rowCount; left++) {
            for (int right = left + 1; right < rowCount; right++) {
                if (sharesColumn(rowColumns.get(left), rowColumns.get(right))) {
                    union(parent, left, right);
                }
            }
        }
        return largestGroup(parent, rowTokens);
    }

    /** 行落在哪些列上（无 token 落在任何列时为空集） */
    private static Set<Integer> columnsOf(List<Token> row, List<TableCandidateDetector.Column> columns) {
        Set<Integer> hit = new LinkedHashSet<>();
        for (int index = 0; index < columns.size(); index++) {
            TableCandidateDetector.Column column = columns.get(index);
            for (Token token : row) {
                if (TableCandidateDetector.contains(column, token)) {
                    hit.add(index);
                    break;
                }
            }
        }
        return hit;
    }

    /** 两行是否共享至少一列 */
    private static boolean sharesColumn(Set<Integer> left, Set<Integer> right) {
        for (Integer column : left) {
            if (right.contains(column)) {
                return true;
            }
        }
        return false;
    }

    /** 并查集合并 */
    private static void union(int[] parent, int left, int right) {
        int leftRoot = find(parent, left);
        int rightRoot = find(parent, right);
        if (leftRoot != rightRoot) {
            parent[rightRoot] = leftRoot;
        }
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        return root;
    }

    /** 最连通块：先比行数，再比 token 数，仍相同取下标更小的 */
    private static List<Integer> largestGroup(int[] parent, List<List<Token>> rowTokens) {
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> groupOf = new ArrayList<>();
        for (int index = 0; index < parent.length; index++) {
            int root = find(parent, index);
            int groupIndex = groupOf.indexOf(root);
            if (groupIndex < 0) {
                groups.add(new ArrayList<>());
                groupOf.add(root);
                groupIndex = groups.size() - 1;
            }
            groups.get(groupIndex).add(index);
        }
        List<Integer> best = groups.getFirst();
        long bestTokens = tokenCount(best, rowTokens);
        for (List<Integer> group : groups) {
            long tokens = tokenCount(group, rowTokens);
            if (group.size() > best.size() || (group.size() == best.size() && tokens > bestTokens)) {
                best = group;
                bestTokens = tokens;
            }
        }
        return List.copyOf(new HashSet<>(best).stream().sorted().toList());
    }

    private static long tokenCount(List<Integer> group, List<List<Token>> rowTokens) {
        long total = 0;
        for (Integer index : group) {
            total += rowTokens.get(index).size();
        }
        return total;
    }

    private static List<Integer> allRows(int rowCount) {
        List<Integer> rows = new ArrayList<>();
        for (int index = 0; index < rowCount; index++) {
            rows.add(index);
        }
        return rows;
    }
}
