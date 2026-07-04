package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveTreeTests {

    @Test
    fun emptyCollection() {
        val moveTree = MoveTree()
        assertTrue(moveTree.isEmpty)
        assertEquals(MoveTree.Index.minimum, moveTree.startIndex)
        assertEquals(MoveTree.Index.minimum, moveTree.endIndex)

        assertFalse(moveTree.hasIndexBefore(MoveTree.Index.minimum))
        assertFalse(moveTree.hasIndexAfter(MoveTree.Index.minimum))
    }

    @Test
    fun subscriptAccess() {
        val moveTree = MoveTree()
        assertNull(moveTree[MoveTree.Index.minimum])

        val e4 = Move("e4", Position.standard)
        moveTree[MoveTree.Index.minimum.next] = e4
        assertEquals(e4, moveTree[MoveTree.Index.minimum.next])
    }

    @Test
    fun sameVariationComparability() {
        val wIndex = MoveTree.Index(4, Piece.Color.white, 2)
        assertTrue(wIndex < wIndex.next)
        assertTrue(wIndex > wIndex.previous)

        val bIndex = MoveTree.Index(4, Piece.Color.black, 2)
        assertTrue(bIndex < bIndex.next)
        assertTrue(bIndex > bIndex.previous)
    }

    @Test
    fun differentVariationComparability() {
        val wIndex1 = MoveTree.Index(4, Piece.Color.white, 2)
        val wIndex2 = MoveTree.Index(4, Piece.Color.white, 3)
        assertTrue(wIndex1 > wIndex2)
        assertTrue(wIndex1.next > wIndex2.next)
        assertTrue(wIndex1.previous > wIndex2.next)
        assertTrue(wIndex1.next > wIndex2.previous)
        assertTrue(wIndex1.previous > wIndex2.previous)

        val bIndex1 = MoveTree.Index(4, Piece.Color.black, 2)
        val bIndex2 = MoveTree.Index(4, Piece.Color.black, 3)
        assertTrue(bIndex1 > bIndex2)
        assertTrue(bIndex1.next > bIndex2.next)
        assertTrue(bIndex1.previous > bIndex2.next)
        assertTrue(bIndex1.next > bIndex2.previous)
        assertTrue(bIndex1.previous > bIndex2.previous)
    }

    @Test
    fun nonexistentIndexBeforeAndAfter() {
        val tree = MoveTree()
        assertEquals(MoveTree.Index.minimum, tree.indexAfter(MoveTree.Index.minimum))
        assertEquals(MoveTree.Index.minimum, tree.indexBefore(MoveTree.Index.minimum))
    }
}
