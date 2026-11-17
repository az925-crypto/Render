import com.catur.modmenu.Chess;
public class Test {
  static void check(boolean c, String m){ if(!c) throw new RuntimeException("FAIL: "+m); System.out.println("ok  "+m); }
  public static void main(String[] a){
    Chess.Position p = Chess.Position.start();
    check(p.toFen().startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"), "start FEN");
    check(Chess.applySan(p,"e4"), "e4");
    check(Chess.applySan(p,"e5"), "e5");
    check(Chess.applySan(p,"Nf3"), "Nf3");
    check(Chess.applySan(p,"Nc6"), "Nc6");
    check(p.toFen().startsWith("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"), "FEN after 4 plies");
    // castling
    Chess.Position c = Chess.Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
    check(Chess.applySan(c,"O-O"), "white O-O");
    check(c.toFen().startsWith("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1"), "after O-O FEN");
    check(Chess.applySan(c,"O-O-O"), "black O-O-O");
    // en passant
    Chess.Position e = Chess.Position.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2");
    check(Chess.applySan(e,"exd6"), "en passant exd6");
    check(e.toFen().startsWith("4k3/8/3P4/8/8/8/8/4K3"), "ep pawn removed");
    // promotion
    Chess.Position pr = Chess.Position.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");
    check(Chess.applySan(pr,"a8=Q"), "promotion a8=Q");
    check(pr.sq[56]=='Q', "queen on a8");
    // check / mate
    Chess.Position m = Chess.Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1");
    Chess.Move mv = null;
    for (Chess.Move x : Chess.legalMoves(m)) if (Chess.uci(x).equals("a1a8")) mv=x;
    check(mv!=null && Chess.san(m,mv).equals("Ra8#"), "SAN check");
    Chess.Position mate = Chess.Position.fromFen("6k1/5ppp/8/8/8/8/8/7K w - - 0 1");
    // scholar-ish mate test: Qh5xf7#
    Chess.Position sc = Chess.Position.start();
    String[] seq={"e4","e5","Bc4","Nc6","Qh5","Nf6","Qxf7#"};
    for(String s:seq) check(Chess.applySan(sc,s), "move "+s);
    check(Chess.legalMoves(sc).isEmpty(), "checkmate detected");
    System.out.println("ALL PASS");
  }
}
