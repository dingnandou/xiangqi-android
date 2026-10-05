package com.yijin.xiangqi.light;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 轻量的离线中国象棋规则和手机对手，无网络、无第三方引擎依赖。 */
public final class Chess {
    public static final int RED = 1, BLACK = -1;
    public static final int KING = 1, ADVISOR = 2, ELEPHANT = 3, HORSE = 4,
            ROOK = 5, CANNON = 6, PAWN = 7;
    private static final int[] VALUE = {0, 20000, 120, 120, 300, 600, 320, 70};
    private static final String[] RED_NAMES = {"", "帅", "仕", "相", "马", "车", "炮", "兵"};
    private static final String[] BLACK_NAMES = {"", "将", "士", "象", "马", "车", "炮", "卒"};
    private Chess() {}

    public static final class Move {
        public final int from, to;
        public Move(int from, int to) { this.from = from; this.to = to; }
        @Override public String toString() { return from + "-" + to; }
    }

    public static int[] initial() {
        int[] b = new int[90];
        int[] back = {ROOK, HORSE, ELEPHANT, ADVISOR, KING, ADVISOR, ELEPHANT, HORSE, ROOK};
        for (int x = 0; x < 9; x++) { b[x] = -back[x]; b[81 + x] = back[x]; }
        b[19] = b[25] = -CANNON;
        b[64] = b[70] = CANNON;
        for (int x = 0; x < 9; x += 2) { b[27 + x] = -PAWN; b[54 + x] = PAWN; }
        return b;
    }

    public static String name(int piece) {
        return piece > 0 ? RED_NAMES[piece] : BLACK_NAMES[-piece];
    }

    private static boolean palace(int x, int y, int side) {
        return x >= 3 && x <= 5 && (side == RED ? y >= 7 && y <= 9 : y >= 0 && y <= 2);
    }

    private static int between(int[] b, int from, int to) {
        int fx = from % 9, fy = from / 9, tx = to % 9, ty = to / 9;
        if (fx != tx && fy != ty) return -1;
        int step = fx == tx ? (ty > fy ? 9 : -9) : (tx > fx ? 1 : -1);
        int count = 0;
        for (int i = from + step; i != to; i += step) if (b[i] != 0) count++;
        return count;
    }

    private static boolean pseudo(int[] b, int from, int to) {
        if (from < 0 || from >= 90 || to < 0 || to >= 90 || from == to || b[from] == 0) return false;
        int piece = b[from], side = piece > 0 ? RED : BLACK;
        if (b[to] * side > 0) return false;
        int fx = from % 9, fy = from / 9, tx = to % 9, ty = to / 9;
        int dx = tx - fx, dy = ty - fy, ax = Math.abs(dx), ay = Math.abs(dy);
        switch (Math.abs(piece)) {
            case KING:
                if (fx == tx && b[to] == -side * KING && between(b, from, to) == 0) return true;
                return ax + ay == 1 && palace(tx, ty, side);
            case ADVISOR:
                return ax == 1 && ay == 1 && palace(tx, ty, side);
            case ELEPHANT:
                return ax == 2 && ay == 2 && (side == RED ? ty >= 5 : ty <= 4)
                        && b[(fy + dy / 2) * 9 + fx + dx / 2] == 0;
            case HORSE:
                if (ax == 2 && ay == 1) return b[fy * 9 + fx + dx / 2] == 0;
                if (ax == 1 && ay == 2) return b[(fy + dy / 2) * 9 + fx] == 0;
                return false;
            case ROOK:
                return (fx == tx || fy == ty) && between(b, from, to) == 0;
            case CANNON:
                return (fx == tx || fy == ty) && between(b, from, to) == (b[to] == 0 ? 0 : 1);
            case PAWN:
                if (dx == 0 && dy == -side) return true;
                return ay == 0 && ax == 1 && (side == RED ? fy <= 4 : fy >= 5);
            default:
                return false;
        }
    }

    public static boolean inCheck(int[] b, int side) {
        int king = -1;
        for (int i = 0; i < 90; i++) if (b[i] == side * KING) { king = i; break; }
        if (king < 0) return true;
        for (int i = 0; i < 90; i++) if (b[i] * side < 0 && pseudo(b, i, king)) return true;
        return false;
    }

    public static boolean legal(int[] b, int from, int to, int side) {
        if (from < 0 || from >= 90 || to < 0 || to >= 90 || b[from] * side <= 0
                || Math.abs(b[to]) == KING || !pseudo(b, from, to)) return false;
        int captured = b[to];
        b[to] = b[from]; b[from] = 0;
        boolean valid = !inCheck(b, side);
        b[from] = b[to]; b[to] = captured;
        return valid;
    }

    public static List<Move> moves(int[] b, int side) {
        List<Move> out = new ArrayList<>();
        for (int from = 0; from < 90; from++) {
            if (b[from] * side <= 0) continue;
            for (int to = 0; to < 90; to++) if (legal(b, from, to, side)) out.add(new Move(from, to));
        }
        return out;
    }

    public static List<Integer> targets(int[] b, int from, int side) {
        List<Integer> out = new ArrayList<>();
        for (int to = 0; to < 90; to++) if (legal(b, from, to, side)) out.add(to);
        return out;
    }

    public static void play(int[] b, Move m) { b[m.to] = b[m.from]; b[m.from] = 0; }

    private static int evaluation(int[] b, int side) {
        int sum = 0;
        for (int i = 0; i < 90; i++) {
            int p = b[i]; if (p == 0) continue;
            int type = Math.abs(p), x = i % 9, y = i / 9;
            int ownSide = p > 0 ? RED : BLACK;
            int progress = ownSide == RED ? 9 - y : y;
            int v = VALUE[type];
            if (type == PAWN) v += progress * 8 + (progress >= 5 ? 40 + (4 - Math.abs(x - 4)) * 6 : 0);
            if (type == HORSE || type == CANNON) v += (4 - Math.abs(x - 4)) * 5;
            sum += ownSide * v;
        }
        return sum * side;
    }

    private static void order(final int[] b, List<Move> list) {
        Collections.sort(list, new Comparator<Move>() {
            @Override public int compare(Move a, Move c) { return priority(c) - priority(a); }
            private int priority(Move m) {
                return b[m.to] == 0 ? 0 : VALUE[Math.abs(b[m.to])] * 10 - VALUE[Math.abs(b[m.from])];
            }
        });
    }

    public static Move bestMove(int[] position, int side, int maxDepth, int budgetMs) {
        return new Search(position.clone(), side, Math.max(1, maxDepth), Math.max(50, budgetMs)).run();
    }

    private static final class SearchStopped extends RuntimeException {
        private static final long serialVersionUID = 1L;
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    private static final class Search {
        final int[] b;
        final int side, maxDepth;
        final long deadline;
        int visits;
        Search(int[] b, int side, int maxDepth, int budgetMs) {
            this.b = b; this.side = side; this.maxDepth = maxDepth;
            deadline = System.nanoTime() + budgetMs * 1000000L;
        }
        void checkTime() {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new SearchStopped();
        }
        Move run() {
            List<Move> root = moves(b, side);
            if (root.isEmpty()) return null;
            order(b, root);
            Move best = root.get(0);
            for (int depth = 1; depth <= maxDepth; depth++) {
                int highest = -1000000;
                Move candidate = best;
                try {
                    for (Move m : root) {
                        checkTime();
                        int p = b[m.from], captured = b[m.to], score;
                        play(b, m);
                        try { score = -negamax(-side, depth - 1, -1000000, -highest, 1); }
                        finally { b[m.from] = p; b[m.to] = captured; }
                        if (score > highest) { highest = score; candidate = m; }
                    }
                    best = candidate;
                    root.remove(best); root.add(0, best);
                } catch (SearchStopped done) { break; }
            }
            return best;
        }
        int negamax(int turn, int depth, int alpha, int beta, int ply) {
            if ((++visits & 15) == 0) checkTime();
            List<Move> list = moves(b, turn);
            if (list.isEmpty()) return -90000 + ply; // 象棋困毙同样判负。
            if (depth <= 0) return quiescence(turn, alpha, beta, 2, ply, list);
            order(b, list);
            for (Move m : list) {
                int p = b[m.from], captured = b[m.to], score;
                play(b, m);
                try { score = -negamax(-turn, depth - 1, -beta, -alpha, ply + 1); }
                finally { b[m.from] = p; b[m.to] = captured; }
                if (score >= beta) return score;
                if (score > alpha) alpha = score;
            }
            return alpha;
        }
        int quiescence(int turn, int alpha, int beta, int left, int ply, List<Move> list) {
            if ((++visits & 15) == 0) checkTime();
            boolean checked = inCheck(b, turn);
            if (left <= 0) return evaluation(b, turn);
            if (!checked) {
                int stand = evaluation(b, turn);
                if (stand >= beta) return stand;
                if (stand > alpha) alpha = stand;
            }
            order(b, list);
            for (Move m : list) {
                if (!checked && b[m.to] == 0) continue;
                int p = b[m.from], captured = b[m.to], score;
                play(b, m);
                try {
                    List<Move> next = moves(b, -turn);
                    score = next.isEmpty() ? 90000 - ply : -quiescence(-turn, -beta, -alpha, left - 1, ply + 1, next);
                } finally { b[m.from] = p; b[m.to] = captured; }
                if (score >= beta) return score;
                if (score > alpha) alpha = score;
            }
            return alpha;
        }
    }
}
