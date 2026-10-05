import com.yijin.xiangqi.light.Chess;
import java.util.List;

/** 独立规则样例、开局节点数及手机对手的运行检查。 */
public final class ChessChecks {
    private static int checks;
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static long perft(int[] board, int side, int depth) {
        if (depth == 0) return 1;
        long sum = 0;
        for (Chess.Move m : Chess.moves(board, side)) {
            int[] copy = board.clone(); Chess.play(copy, m);
            sum += perft(copy, -side, depth - 1);
        }
        return sum;
    }
    private static int[] base() {
        int[] b = new int[90]; b[4] = -Chess.KING; b[85] = Chess.KING; b[49] = Chess.PAWN; return b;
    }
    public static void main(String[] args) throws Exception {
        int[] b = Chess.initial();
        require(Chess.moves(b, Chess.RED).size() == 44, "Opening legal moves must be 44");
        require(perft(b, Chess.RED, 2) == 1920, "Opening depth-2 nodes must be 1920");
        require(perft(b, Chess.RED, 3) == 79666, "Opening depth-3 nodes must be 79666");
        require(!Chess.inCheck(b, Chess.RED) && !Chess.inCheck(b, Chess.BLACK), "Initial kings are safe");
        require(Chess.legal(b, 82, 63, Chess.RED), "Horse can move when leg is empty");
        b[73] = Chess.PAWN;
        require(!Chess.legal(b, 82, 63, Chess.RED), "Horse leg blocks move");
        b = base(); b[64] = Chess.CANNON; b[10] = -Chess.ROOK;
        require(!Chess.legal(b, 64, 10, Chess.RED), "Cannon cannot capture without screen");
        b[37] = -Chess.PAWN;
        require(Chess.legal(b, 64, 10, Chess.RED), "Cannon captures over exactly one screen");
        b[46] = -Chess.PAWN;
        require(!Chess.legal(b, 64, 10, Chess.RED), "Two screens block cannon capture");
        b = base(); b[54] = Chess.PAWN;
        require(!Chess.legal(b, 54, 55, Chess.RED), "Pawn cannot move sideways before river");
        b[54] = 0; b[36] = Chess.PAWN;
        require(Chess.legal(b, 36, 37, Chess.RED), "Pawn can move sideways after river");
        require(!Chess.legal(b, 36, 45, Chess.RED), "Pawn cannot move backwards");
        b = base(); b[65] = Chess.ELEPHANT;
        require(Chess.legal(b, 65, 45, Chess.RED), "Elephant can move with empty eye");
        b[55] = Chess.PAWN;
        require(!Chess.legal(b, 65, 45, Chess.RED), "Elephant eye blocks move");
        b = base(); b[45] = Chess.ELEPHANT;
        require(!Chess.legal(b, 45, 29, Chess.RED), "Elephant cannot cross river");
        b = base(); b[84] = Chess.ADVISOR;
        require(Chess.legal(b, 84, 76, Chess.RED), "Advisor stays in palace");
        require(!Chess.legal(b, 84, 74, Chess.RED), "Advisor cannot leave palace");
        b = new int[90]; b[4] = -Chess.KING; b[85] = Chess.KING; b[76] = Chess.ROOK;
        require(!Chess.legal(b, 76, 75, Chess.RED), "Cannot expose facing kings");
        b[76] = 0;
        require(Chess.inCheck(b, Chess.RED) && Chess.inCheck(b, Chess.BLACK), "Facing kings threaten each other");
        b = base(); b[0] = Chess.ROOK; b[9] = Chess.ROOK;
        require(Chess.inCheck(b, Chess.BLACK), "Mate sample is check");
        require(Chess.moves(b, Chess.BLACK).isEmpty(), "Mate sample has no legal reply");
        b = base(); b[9] = Chess.ROOK; b[21] = Chess.ROOK; b[23] = Chess.ROOK;
        require(!Chess.inCheck(b, Chess.BLACK), "Stalemate sample is not check");
        require(Chess.moves(b, Chess.BLACK).isEmpty(), "Xiangqi stalemate has no legal reply");
        long start = System.nanoTime();
        b = Chess.initial();
        Chess.Move phoneMove = Chess.bestMove(b, Chess.BLACK, 3, 250);
        require(phoneMove != null && Chess.legal(b, phoneMove.from, phoneMove.to, Chess.BLACK), "Phone reply is legal");
        require((System.nanoTime() - start) / 1000000 < 2000, "Phone move respects bounded waiting");
        int side = Chess.RED;
        for (int ply = 0; ply < 40; ply++) {
            List<Chess.Move> legal = Chess.moves(b, side);
            if (legal.isEmpty()) break;
            Chess.Move m = Chess.bestMove(b, side, 1, 80);
            require(m != null && Chess.legal(b, m.from, m.to, side), "Legal self-play move " + ply);
            Chess.play(b, m);
            require(!Chess.inCheck(b, side), "Self-play does not leave own king in check " + ply);
            side = -side;
        }
        Thread work = new Thread(() -> Chess.bestMove(Chess.initial(), Chess.RED, 8, 10000));
        work.start(); Thread.sleep(30); work.interrupt(); work.join(1500);
        require(!work.isAlive(), "Search can be cancelled promptly");
        System.out.println("CHESS_CHECKS_OK: " + checks + " checks passed");
    }
}
