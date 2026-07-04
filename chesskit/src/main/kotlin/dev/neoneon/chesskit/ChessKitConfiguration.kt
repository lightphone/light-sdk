package dev.neoneon.chesskit

/** Stores configuration options for the `ChessKit` package. */
object ChessKitConfiguration {

    /**
     * Configuration options for printing [Board] and [Position] objects, useful for debugging.
     */
    var printOptions: PrintOptions = PrintOptions()

    data class PrintOptions(
        /**
         * Whether to print pieces as letters ([PrintMode.LETTER]) or unicode graphics
         * ([PrintMode.GRAPHIC]) when printing [Board] and [Position] objects.
         *
         * The default value is [PrintMode.GRAPHIC].
         */
        var mode: PrintMode = PrintMode.graphic,
        /**
         * Whether to print rank and file labels when printing [Board] and [Position] objects.
         *
         * The default value is `true`.
         */
        var showCoordinates: Boolean = true,
    ) {
        /** ChessKit `printMode` options. */
        enum class PrintMode {
            /** Print pieces as unicode graphic characters, e.g. ♟, ♞, ♝, ♜, ♛, ♚. */
            graphic,

            /**
             * Print pieces as FEN letters, e.g. P, N, B, R, Q, K.
             *
             * Uppercase letters are white pieces and lowercase letters are black pieces.
             */
            letter,
        }
    }
}
