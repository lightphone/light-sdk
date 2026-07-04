package dev.neoneon.chesskit

val Position.Companion.complex: Position
    get() = FENParser.parse("r1b1k1nr/p2p1pNp/n2B4/1p1NP2P/6P1/3P1Q2/P1P1K3/q5b1 b kq - 0 20")!!

val Position.Companion.ep: Position
    get() = FENParser.parse("rnbqkbnr/ppppp1pp/8/8/4Pp2/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")!!

val Position.Companion.castlingSample: Position
    get() = FENParser.parse("4k2r/6r1/8/8/8/8/3R4/R3K3 w Qk - 0 1")!!

val Position.Companion.fiftyMove: Position
    get() = FENParser.parse("8/5k2/3p4/1p1Pp2p/pP2Pp1P/P4P1K/8/8 b - - 99 50")!!
