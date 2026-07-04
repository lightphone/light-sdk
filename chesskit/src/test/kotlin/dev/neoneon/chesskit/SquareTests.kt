package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals

class SquareTests {

    @Test
    fun notation() {
        assertEquals("a1", Square.a1.notation)
        assertEquals("h1", Square.h1.notation)
        assertEquals("a8", Square.a8.notation)
        assertEquals("h8", Square.h8.notation)

        assertEquals(Square.a1, Square("a1"))
        assertEquals(Square.h1, Square("h1"))
        assertEquals(Square.a8, Square("a8"))
        assertEquals(Square.h8, Square("h8"))
    }

    @Test
    fun invalidNotation() {
        assertEquals(Square.a1, Square("invalid"))
    }

    @Test
    fun squareColor() {
        assertEquals(Square.Color.dark, Square.a1.color)
        assertEquals(Square.Color.light, Square.h1.color)
        assertEquals(Square.Color.light, Square.a8.color)
        assertEquals(Square.Color.dark, Square.h8.color)
    }

    @Test
    fun fileNumber() {
        assertEquals(1, Square.File.a.number)
        assertEquals(8, Square.File.h.number)

        assertEquals(Square.File.a, Square.File(1))
        assertEquals(Square.File.b, Square.File(2))
        assertEquals(Square.File.c, Square.File(3))
        assertEquals(Square.File.d, Square.File(4))
        assertEquals(Square.File.e, Square.File(5))
        assertEquals(Square.File.f, Square.File(6))
        assertEquals(Square.File.g, Square.File(7))
        assertEquals(Square.File.h, Square.File(8))
    }

    @Test
    fun invalidFileNumber() {
        assertEquals(Square.File.a, Square.File(-10))
        assertEquals(Square.File.h, Square.File(100))
    }

    @Test
    fun directionalSquares() {
        assertEquals(Square.a1, Square.a1.left)
        assertEquals(Square.a1, Square.b1.left)
        assertEquals(Square.g1, Square.h1.left)

        assertEquals(Square.b1, Square.a1.right)
        assertEquals(Square.h1, Square.g1.right)
        assertEquals(Square.h1, Square.h1.right)

        assertEquals(Square.a8, Square.a8.up)
        assertEquals(Square.a8, Square.a7.up)
        assertEquals(Square.a2, Square.a1.up)

        assertEquals(Square.a1, Square.a1.down)
        assertEquals(Square.a1, Square.a2.down)
        assertEquals(Square.a7, Square.a8.down)
    }
}
