package com.catur.modmenu;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, self-contained chess rules engine.
 *
 * Board indexing: index = rank * 8 + file, rank 0 is rank 1, file 0 is a.
 * a1 = 0, h1 = 7, a8 = 56, h8 = 63.
 * Pieces: uppercase = white, lowercase = black, '.' = empty.
 */
final class Chess {

    static final char EMPTY = '.';

    static final class Move {
        final int from;
        final int to;
        final char promo;       // '.', or q/r/b/n (uppercase = white promotion)
        final boolean enPassant;
        final boolean castleK;
        final boolean castleQ;

        Move(int from, int to, char promo, boolean enPassant,
             boolean castleK, boolean castleQ) {
            this.from = from;
            this.to = to;
            this.promo = promo;
            this.enPassant = enPassant;
            this.castleK = castleK;
            this.castleQ = castleQ;
        }
    }

    static final class Position {
        final char[] sq = new char[64];
        Position() { java.util.Arrays.fill(sq, EMPTY); }
        boolean whiteToMove = true;
        int castling = 0b1111;   // 1=WK, 2=WQ, 4=BK, 8=BQ
        int ep = -1;
        int halfmove = 0;
        int fullmove = 1;

        Position copy() {
            Position p = new Position();
            System.arraycopy(sq, 0, p.sq, 0, 64);
            p.whiteToMove = whiteToMove;
            p.castling = castling;
            p.ep = ep;
            p.halfmove = halfmove;
            p.fullmove = fullmove;
            return p;
        }

        static Position start() {
            return fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        }

        static Position fromFen(String fen) {
            Position p = new Position();
            String[] parts = fen.trim().split("\\s+");
            if (parts.length < 4) throw new IllegalArgumentException("bad FEN");
            int rank = 7, file = 0;
            for (char c : parts[0].toCharArray()) {
                if (c == '/') { rank--; file = 0; }
                else if (c >= '1' && c <= '8') file += c - '0';
                else { p.sq[rank * 8 + file] = c; file++; }
            }
            p.whiteToMove = parts[1].equals("w");
            p.castling = 0;
            if (!parts[2].equals("-")) {
                for (char c : parts[2].toCharArray()) {
                    if (c == 'K') p.castling |= 1;
                    else if (c == 'Q') p.castling |= 2;
                    else if (c == 'k') p.castling |= 4;
                    else if (c == 'q') p.castling |= 8;
                }
            }
            p.ep = parts[3].equals("-") ? -1 : square(parts[3]);
            if (parts.length > 4) p.halfmove = parseInt(parts[4], 0);
            if (parts.length > 5) p.fullmove = parseInt(parts[5], 1);
            return p;
        }

        String toFen() {
            StringBuilder b = new StringBuilder(90);
            for (int rank = 7; rank >= 0; rank--) {
                int empty = 0;
                for (int file = 0; file < 8; file++) {
                    char c = sq[rank * 8 + file];
                    if (c == EMPTY) empty++;
                    else {
                        if (empty > 0) { b.append(empty); empty = 0; }
                        b.append(c);
                    }
                }
                if (empty > 0) b.append(empty);
                if (rank > 0) b.append('/');
            }
            b.append(whiteToMove ? " w " : " b ");
            if (castling == 0) b.append('-');
            else {
                if ((castling & 1) != 0) b.append('K');
                if ((castling & 2) != 0) b.append('Q');
                if ((castling & 4) != 0) b.append('k');
                if ((castling & 8) != 0) b.append('q');
            }
            b.append(' ');
            b.append(ep < 0 ? "-" : name(ep));
            b.append(' ').append(halfmove).append(' ').append(fullmove);
            return b.toString();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int square(String s) {
        return (s.charAt(1) - '1') * 8 + (s.charAt(0) - 'a');
    }

    private static String name(int sq) {
        return "" + (char) ('a' + sq % 8) + (char) ('1' + sq / 8);
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
    }

    static boolean isWhite(char p) { return p != EMPTY && Character.isUpperCase(p); }
    static boolean isBlack(char p) { return p != EMPTY && Character.isLowerCase(p); }
    private static boolean sameColor(char a, char b) {
        return a != EMPTY && b != EMPTY && Character.isUpperCase(a) == Character.isUpperCase(b);
    }

    private static int file(int s) { return s % 8; }
    private static int rank(int s) { return s / 8; }

    private static final int[][] KNIGHT_DIRS = {
            {-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}
    };
    private static final int[][] ROOK_DIRS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] BISHOP_DIRS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    private static final int[][] QUEEN_DIRS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };
    private static final int[][] KING_DIRS = {
            {-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}
    };

    private static boolean onBoard(int f, int r) { return f >= 0 && f < 8 && r >= 0 && r < 8; }

    /** Is square `sq` attacked by `byWhite`? */
    static boolean attacked(Position p, int sq, boolean byWhite) {
        char[] b = p.sq;
        int f = file(sq), r = rank(sq);

        // pawns: a white pawn attacks two squares up, a black pawn two squares down
        int pawnRank = byWhite ? r - 1 : r + 1;
        if (pawnRank >= 0 && pawnRank < 8) {
            for (int df : new int[]{-1, 1}) {
                int pf = f + df;
                if (!onBoard(pf, pawnRank)) continue;
                char c = b[pawnRank * 8 + pf];
                if (c != EMPTY && (byWhite ? isWhite(c) : isBlack(c))
                        && Character.toUpperCase(c) == 'P') return true;
            }
        }

        // knights
        for (int[] d : KNIGHT_DIRS) {
            int nf = f + d[0], nr = r + d[1];
            if (!onBoard(nf, nr)) continue;
            char c = b[nr * 8 + nf];
            if (c != EMPTY && (byWhite ? isWhite(c) : isBlack(c))
                    && Character.toUpperCase(c) == 'N') return true;
        }

        // king
        for (int[] d : KING_DIRS) {
            int nf = f + d[0], nr = r + d[1];
            if (!onBoard(nf, nr)) continue;
            char c = b[nr * 8 + nf];
            if (c != EMPTY && (byWhite ? isWhite(c) : isBlack(c))
                    && Character.toUpperCase(c) == 'K') return true;
        }

        // sliders
        for (int[] d : ROOK_DIRS) {
            if (slideAttacks(b, f, r, d[0], d[1], byWhite, "RQ")) return true;
        }
        for (int[] d : BISHOP_DIRS) {
            if (slideAttacks(b, f, r, d[0], d[1], byWhite, "BQ")) return true;
        }
        return false;
    }

    private static boolean slideAttacks(char[] b, int f, int r, int df, int dr,
                                        boolean byWhite, String wanted) {
        int nf = f + df, nr = r + dr;
        while (onBoard(nf, nr)) {
            char c = b[nr * 8 + nf];
            if (c != EMPTY) {
                if ((byWhite ? isWhite(c) : isBlack(c))
                        && wanted.indexOf(Character.toUpperCase(c)) >= 0) return true;
                return false;
            }
            nf += df; nr += dr;
        }
        return false;
    }

    private static int kingSquare(Position p, boolean white) {
        char want = white ? 'K' : 'k';
        for (int i = 0; i < 64; i++) if (p.sq[i] == want) return i;
        return -1;
    }

    // ------------------------------------------------------------ move making

    private static final class Undo {
        char captured;
        int castling, ep, halfmove, fullmove;
        boolean whiteToMove;
    }

    private static void make(Position p, Move m, Undo u) {
        u.captured = p.sq[m.to];
        u.castling = p.castling;
        u.ep = p.ep;
        u.halfmove = p.halfmove;
        u.fullmove = p.fullmove;
        u.whiteToMove = p.whiteToMove;

        char piece = p.sq[m.from];
        p.sq[m.from] = EMPTY;

        if (m.enPassant) {
            int cap = p.whiteToMove ? m.to - 8 : m.to + 8;
            u.captured = p.sq[cap];
            p.sq[cap] = EMPTY;
        }
        p.sq[m.to] = m.promo != '.' ? m.promo : piece;

        if (m.castleK) {
            char rook = p.whiteToMove ? 'R' : 'r';
            int rf = p.whiteToMove ? 7 : 63;
            int rt = p.whiteToMove ? 5 : 61;
            p.sq[rf] = EMPTY; p.sq[rt] = rook;
        } else if (m.castleQ) {
            char rook = p.whiteToMove ? 'R' : 'r';
            int rf = p.whiteToMove ? 0 : 56;
            int rt = p.whiteToMove ? 3 : 59;
            p.sq[rf] = EMPTY; p.sq[rt] = rook;
        }

        // castling rights
        if (piece == 'K') p.castling &= ~3;
        if (piece == 'k') p.castling &= ~12;
        if (m.from == 0 || m.to == 0) p.castling &= ~2;
        if (m.from == 7 || m.to == 7) p.castling &= ~1;
        if (m.from == 56 || m.to == 56) p.castling &= ~8;
        if (m.from == 63 || m.to == 63) p.castling &= ~4;

        // en passant target
        p.ep = -1;
        if (Character.toUpperCase(piece) == 'P' && Math.abs(m.to - m.from) == 16)
            p.ep = (m.to + m.from) / 2;

        if (Character.toUpperCase(piece) == 'P' || u.captured != EMPTY) p.halfmove = 0;
        else p.halfmove++;
        if (!p.whiteToMove) p.fullmove++;
        p.whiteToMove = !p.whiteToMove;
    }

    private static void unmake(Position p, Move m, Undo u) {
        p.whiteToMove = u.whiteToMove;
        p.fullmove = u.fullmove;
        p.halfmove = u.halfmove;
        p.ep = u.ep;
        p.castling = u.castling;

        char piece = p.sq[m.to];
        p.sq[m.to] = EMPTY;
        p.sq[m.from] = m.promo != '.' ? (p.whiteToMove ? 'P' : 'p') : piece;

        if (m.enPassant) {
            int cap = p.whiteToMove ? m.to - 8 : m.to + 8;
            p.sq[cap] = u.captured;
        } else {
            p.sq[m.to] = u.captured;
        }
        if (m.promo != '.') p.sq[m.from] = p.whiteToMove ? 'P' : 'p';
        else p.sq[m.from] = piece;

        if (m.castleK) {
            char rook = p.whiteToMove ? 'R' : 'r';
            int rf = p.whiteToMove ? 7 : 63;
            int rt = p.whiteToMove ? 5 : 61;
            p.sq[rt] = EMPTY; p.sq[rf] = rook;
        } else if (m.castleQ) {
            char rook = p.whiteToMove ? 'R' : 'r';
            int rf = p.whiteToMove ? 0 : 56;
            int rt = p.whiteToMove ? 3 : 59;
            p.sq[rt] = EMPTY; p.sq[rf] = rook;
        }
    }

    // --------------------------------------------------------- move generation

    static List<Move> legalMoves(Position p) {
        List<Move> pseudo = pseudoMoves(p);
        List<Move> out = new ArrayList<>(pseudo.size());
        Undo u = new Undo();
        for (Move m : pseudo) {
            make(p, m, u);
            int k = kingSquare(p, !p.whiteToMove); // king that just moved
            boolean safe = k < 0 || !attacked(p, k, p.whiteToMove);
            unmake(p, m, u);
            if (safe) out.add(m);
        }
        return out;
    }

    private static List<Move> pseudoMoves(Position p) {
        List<Move> out = new ArrayList<>();
        boolean w = p.whiteToMove;
        char[] b = p.sq;
        for (int from = 0; from < 64; from++) {
            char c = b[from];
            if (c == EMPTY || (w ? isBlack(c) : isWhite(c))) continue;
            char u = Character.toUpperCase(c);
            int f = file(from), r = rank(from);

            if (u == 'P') {
                int dir = w ? 1 : -1;
                int startRank = w ? 1 : 6;
                int promoRank = w ? 7 : 0;

                int one = from + dir * 8;
                if (one >= 0 && one < 64 && b[one] == EMPTY) {
                    if (rank(one) == promoRank) addPromotions(out, from, one, w);
                    else {
                        out.add(new Move(from, one, '.', false, false, false));
                        int two = from + dir * 16;
                        if (r == startRank && b[two] == EMPTY)
                            out.add(new Move(from, two, '.', false, false, false));
                    }
                }
                for (int df : new int[]{-1, 1}) {
                    int nf = f + df, nr = r + dir;
                    if (!onBoard(nf, nr)) continue;
                    int to = nr * 8 + nf;
                    if (b[to] != EMPTY && (w ? isBlack(b[to]) : isWhite(b[to]))) {
                        if (nr == promoRank) addPromotions(out, from, to, w);
                        else out.add(new Move(from, to, '.', false, false, false));
                    } else if (p.ep == to) {
                        out.add(new Move(from, to, '.', true, false, false));
                    }
                }
            } else if (u == 'N') {
                for (int[] d : KNIGHT_DIRS) {
                    int nf = f + d[0], nr = r + d[1];
                    if (!onBoard(nf, nr)) continue;
                    int to = nr * 8 + nf;
                    if (b[to] == EMPTY || (w ? isBlack(b[to]) : isWhite(b[to])))
                        out.add(new Move(from, to, '.', false, false, false));
                }
            } else if (u == 'K') {
                for (int[] d : KING_DIRS) {
                    int nf = f + d[0], nr = r + d[1];
                    if (!onBoard(nf, nr)) continue;
                    int to = nr * 8 + nf;
                    if (b[to] == EMPTY || (w ? isBlack(b[to]) : isWhite(b[to])))
                        out.add(new Move(from, to, '.', false, false, false));
                }
                // castling
                boolean inCheck = attacked(p, from, !w);
                if (!inCheck) {
                    int kBit = w ? 1 : 4, qBit = w ? 2 : 8;
                    int home = w ? 4 : 60;
                    if (from == home && (p.castling & kBit) != 0) {
                        if (b[home + 1] == EMPTY && b[home + 2] == EMPTY
                                && !attacked(p, home + 1, !w) && !attacked(p, home + 2, !w))
                            out.add(new Move(home, home + 2, '.', false, true, false));
                    }
                    if (from == home && (p.castling & qBit) != 0) {
                        if (b[home - 1] == EMPTY && b[home - 2] == EMPTY && b[home - 3] == EMPTY
                                && !attacked(p, home - 1, !w) && !attacked(p, home - 2, !w))
                            out.add(new Move(home, home - 2, '.', false, false, true));
                    }
                }
            } else {
                int[][] dirs = u == 'R' ? ROOK_DIRS : u == 'B' ? BISHOP_DIRS : QUEEN_DIRS;
                for (int[] d : dirs) {
                    int nf = f + d[0], nr = r + d[1];
                    while (onBoard(nf, nr)) {
                        int to = nr * 8 + nf;
                        if (b[to] == EMPTY) out.add(new Move(from, to, '.', false, false, false));
                        else {
                            if (w ? isBlack(b[to]) : isWhite(b[to]))
                                out.add(new Move(from, to, '.', false, false, false));
                            break;
                        }
                        nf += d[0]; nr += d[1];
                    }
                }
            }
        }
        return out;
    }

    private static void addPromotions(List<Move> out, int from, int to, boolean w) {
        char[] ps = w ? new char[]{'Q', 'R', 'B', 'N'} : new char[]{'q', 'r', 'b', 'n'};
        for (char p : ps) out.add(new Move(from, to, p, false, false, false));
    }

    // -------------------------------------------------------------------- SAN

    /** SAN without the trailing + / # — used to match against the move list. */
    static String sanBase(Position p, Move m) {
        if (m.castleK) return "O-O";
        if (m.castleQ) return "O-O-O";
        char piece = p.sq[m.from];
        char u = Character.toUpperCase(piece);
        String to = name(m.to);
        StringBuilder b = new StringBuilder(8);

        if (u == 'P') {
            if (p.sq[m.to] != EMPTY || m.enPassant) b.append((char) ('a' + file(m.from)));
            if (p.sq[m.to] != EMPTY || m.enPassant) b.append('x');
            b.append(to);
            if (m.promo != '.') b.append('=').append(Character.toUpperCase(m.promo));
        } else {
            b.append(u);
            List<Move> alts = legalMoves(p);
            boolean sameFile = false, sameRank = false;
            int others = 0;
            for (Move o : alts) {
                if (o.from == m.from || o.to != m.to) continue;
                if (p.sq[o.from] != piece) continue;
                others++;
                if (file(o.from) == file(m.from)) sameFile = true;
                if (rank(o.from) == rank(m.from)) sameRank = true;
            }
            if (others > 0) {
                if (!sameFile) b.append((char) ('a' + file(m.from)));
                else if (!sameRank) b.append((char) ('1' + rank(m.from)));
                else b.append(name(m.from));
            }
            if (p.sq[m.to] != EMPTY) b.append('x');
            b.append(to);
        }
        return b.toString();
    }

    static String san(Position p, Move m) {
        String base = sanBase(p, m);
        Undo u = new Undo();
        make(p, m, u);
        // after make, p.whiteToMove is the opponent; check their king
        int k = kingSquare(p, p.whiteToMove);
        boolean check = k >= 0 && attacked(p, k, !p.whiteToMove);
        boolean mate = false;
        if (check) mate = legalMoves(p).isEmpty();
        unmake(p, m, u);
        return base + (check ? (mate ? "#" : "+") : "");
    }

    /** Apply a SAN token. Returns false when the token does not fit the position. */
    static boolean applySan(Position p, String token) {
        if (token == null) return false;
        String s = token.trim()
                .replace("0-0-0", "O-O-O").replace("0-0", "O-O")
                .replace("+", "").replace("#", "")
                .replace("!", "").replace("?", "").replace(" ", "")
                .replace("e.p.", "").trim();
        if (s.isEmpty() || s.equals("1-0") || s.equals("0-1") || s.equals("1/2-1/2")) return false;
        for (Move m : legalMoves(p)) {
            String base = sanBase(p, m);
            if (base.equalsIgnoreCase(s)) {
                make(p, m, new Undo());
                return true;
            }
        }
        return false;
    }

    static String uci(Move m) {
        return name(m.from) + name(m.to) + (m.promo == '.' ? "" : Character.toLowerCase(m.promo));
    }

    /** Find the legal move whose UCI string matches. */
    static Move fromUci(Position p, String uci) {
        if (uci == null || uci.length() < 4) return null;
        for (Move m : legalMoves(p)) if (uci(m).equals(uci)) return m;
        return null;
    }

    private Chess() {}
}
