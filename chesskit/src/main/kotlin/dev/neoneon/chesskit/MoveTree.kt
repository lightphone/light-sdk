package dev.neoneon.chesskit

/**
 * A tree-like data structure that represents the moves of a chess game.
 *
 * The tree maintains the move order including variations and provides index-based
 * access for any element in the tree.
 */
class MoveTree {

    /** The index of the root of the move tree. Defaults to [Index.minimum]. */
    var minimumIndex: Index = Index.minimum

    /** The last index of the main variation of the move tree. */
    var lastMainVariationIndex: Index = Index.minimum
        private set

    /** Map representation of the tree for faster access. */
    internal val dictionary: MutableMap<Index, Node> = mutableMapOf()

    /** The root node of the tree. */
    private var root: Node? = null

    /** All the indices of all the moves stored in the tree. */
    val indices: List<Index> get() = dictionary.keys.toList()

    /**
     * Adds a move to the move tree.
     *
     * @param move The move to add to the tree.
     * @param toParentIndex The index of the parent move, if applicable. If `null`,
     * the move tree is cleared and the provided move is set to the head of the move tree.
     * @return The move index resulting from the addition of the move.
     */
    fun add(move: Move, toParentIndex: Index? = null): Index {
        val newNode = Node(move)
        val root = this.root

        if (root == null || toParentIndex == null) {
            val index = minimumIndex.next

            newNode.index = index
            this.root = newNode

            dictionary.clear()
            dictionary[index] = newNode

            if (index.variation == Index.mainVariation) {
                lastMainVariationIndex = index
            }
            return index
        }

        val parent = dictionary[toParentIndex] ?: root
        newNode.previous = parent

        var newIndex = toParentIndex.next

        if (parent.next == null) {
            parent.next = newNode
        } else {
            parent.children.add(newNode)
            while (indices.contains(newIndex)) {
                newIndex = newIndex.copy(variation = newIndex.variation + 1)
            }
        }

        dictionary[newIndex] = newNode
        newNode.index = newIndex

        if (newIndex.variation == Index.mainVariation) {
            lastMainVariationIndex = newIndex
        }

        return newIndex
    }

    /**
     * Returns the index matching [move] in the next or child moves of the move
     * contained at [forIndex].
     */
    fun nextIndex(containing: Move, forIndex: Index): Index? {
        val node = dictionary[forIndex]
            ?: return if (forIndex == minimumIndex && root?.move == containing) root?.index else null

        val next = node.next
        return if (next != null && next.move == containing) {
            next.index
        } else {
            node.children.firstOrNull { it.move == containing }?.index
        }
    }

    /**
     * Provides a single history for a given index.
     *
     * @return A list of move indices sorted from beginning to end with the end being
     * the provided [forIndex].
     *
     * For chess this would represent a list of all the move indices from the starting
     * move until the move defined by [forIndex], accounting for any branching
     * variations in between.
     */
    fun history(forIndex: Index): List<Index> {
        val startIndex = if (forIndex == Index.minimum) Index.minimum.next else forIndex
        var currentNode = dictionary[startIndex]
        val history = mutableListOf<Index>()

        while (currentNode != null) {
            history.add(currentNode.index)
            currentNode = currentNode.previous
        }

        return history.reversed()
    }

    /**
     * Provides a single future for a given index.
     *
     * @return A list of move indices sorted from beginning to end.
     *
     * For chess this would represent a list of all the move indices from the move
     * after the move defined by [forIndex] to the last move of the variation.
     */
    fun future(forIndex: Index): List<Index> {
        val startIndex = if (forIndex == Index.minimum) Index.minimum.next else forIndex
        var currentNode = dictionary[startIndex]
        val future = mutableListOf<Index>()

        while (currentNode != null) {
            currentNode = currentNode.next
            if (currentNode != null) {
                future.add(currentNode.index)
            }
        }

        return future
    }

    /**
     * Returns the full variation for a move at the provided [forIndex].
     *
     * This returns the sum of [history] and [future].
     */
    fun fullVariation(forIndex: Index): List<Index> = history(forIndex) + future(forIndex)

    private fun indicesBetween(start: Index, end: Index): List<Index> {
        val result = mutableListOf<Index>()

        val endNode = dictionary[end]
        var currentNode = dictionary[start]

        while (currentNode != endNode) {
            if (currentNode != null) {
                result.add(currentNode.index)
            }
            currentNode = currentNode?.previous
        }

        return result
    }

    /**
     * Provides the shortest path through the move tree from the given start and end indices.
     *
     * @return A list of [PathStep]s starting with the index after [startIndex] and
     * ending with [endIndex]. If [startIndex] and [endIndex] are the same, an empty
     * list is returned.
     *
     * The purpose of this path is to return the indices of the moves required to go
     * from the current position at [startIndex] and end up with the final position at
     * [endIndex], so [startIndex] is included in the returned list, but [endIndex] is
     * not. The path direction included with the index indicates the direction to move
     * to get to the next index.
     */
    fun path(startIndex: Index, endIndex: Index): List<PathStep> {
        var results = listOf<PathStep>()
        val startHistory = history(startIndex)
        val endHistory = history(endIndex)

        if (startIndex == endIndex) {
            // keep results empty
        } else if (startHistory.contains(endIndex)) {
            results = indicesBetween(startIndex, endIndex).map { PathStep(PathDirection.reverse, it) }
        } else if (endHistory.contains(startIndex)) {
            results = indicesBetween(endIndex, startIndex).map { PathStep(PathDirection.forward, it) }.reversed()
        } else {
            // lowest common ancestor
            val lca = startHistory.zip(endHistory).lastOrNull { it.first == it.second }?.first
            val startLCAIndex = lca?.let { l -> startHistory.indexOfFirst { it == l } } ?: -1
            val endLCAIndex = lca?.let { l -> endHistory.indexOfFirst { it == l } } ?: -1

            if (lca == null || startLCAIndex < 0 || endLCAIndex < 0) {
                return emptyList()
            }

            val startToLCAPath = startHistory.subList(startLCAIndex, startHistory.size)
                .reversed() // reverse since history is in ascending order
                .dropLast(1) // drop LCA; to be included in the next list
                .map { PathStep(PathDirection.reverse, it) }

            val lcaToEndPath = endHistory.subList(endLCAIndex, endHistory.size)
                .map { PathStep(PathDirection.forward, it) }

            results = startToLCAPath + lcaToEndPath
        }

        return results
    }

    /** A step in a [MoveTree] path, with a [direction] and [index]. */
    data class PathStep(val direction: PathDirection, val index: Index)

    /** The direction of the [MoveTree] path. */
    enum class PathDirection { forward, reverse }

    /** Whether the tree is empty or not. */
    val isEmpty: Boolean get() = root == null

    /**
     * Annotates the move at the provided [moveAt] index.
     *
     * @return The move updated with the given annotations.
     */
    fun annotate(moveAt: Index, assessment: Move.Assessment = Move.Assessment.`null`, comment: String = ""): Move? {
        val node = dictionary[moveAt] ?: return null
        node.move.assessment = assessment
        node.move.comment = comment
        return node.move
    }

    /**
     * Annotates the position at the provided [positionAt] index.
     *
     * This value is stored in the move tree to generate an accurate PGN representation
     * with [pgnRepresentation].
     */
    fun annotate(positionAt: Index, assessment: Position.Assessment) {
        dictionary[positionAt]?.positionAssessment = assessment
    }

    // MARK: - PGN

    /** An element for representing the [MoveTree] in PGN (Portable Game Notation) format. */
    sealed interface PGNElement {
        /** e.g. `1.` */
        data class whiteNumber(val number: Int) : PGNElement

        /** e.g. `1...` */
        data class blackNumber(val number: Int) : PGNElement

        /** e.g. `e4` */
        data class move(val move: Move, val index: Index) : PGNElement

        /** e.g. `$10` */
        data class positionAssessment(val assessment: Position.Assessment) : PGNElement

        /** e.g. `(` */
        data object variationStart : PGNElement

        /** e.g. `)` */
        data object variationEnd : PGNElement
    }

    private fun pgn(node: Node?): List<PGNElement> {
        if (node == null) return emptyList()
        val result = mutableListOf<PGNElement>()

        when (node.index.color) {
            Piece.Color.white -> result.add(PGNElement.whiteNumber(node.index.number))
            Piece.Color.black -> result.add(PGNElement.blackNumber(node.index.number))
        }

        result.add(PGNElement.move(node.move, node.index))
        if (node.positionAssessment != Position.Assessment.`null`) {
            result.add(PGNElement.positionAssessment(node.positionAssessment))
        }

        var currentNode = node.next
        var previousIndex = node.index

        while (currentNode != null) {
            val currentIndex = currentNode.index

            if (previousIndex.number < currentIndex.number) {
                result.add(PGNElement.whiteNumber(currentIndex.number))
            }

            result.add(PGNElement.move(currentNode.move, currentIndex))

            if (currentNode.positionAssessment != Position.Assessment.`null`) {
                result.add(PGNElement.positionAssessment(currentNode.positionAssessment))
            }

            // recursively generate PGN for all child nodes
            currentNode.previous?.children?.forEach { child ->
                result.add(PGNElement.variationStart)
                result.addAll(pgn(child))
                result.add(PGNElement.variationEnd)
            }

            previousIndex = currentIndex
            currentNode = currentNode.next
        }

        return result
    }

    /** Returns the [MoveTree] as a list of PGN (Portable Game Format) elements. */
    val pgnRepresentation: List<PGNElement> get() = pgn(root)

    // MARK: - Index-based access

    val startIndex: Index get() = minimumIndex
    val endIndex: Index get() = lastMainVariationIndex

    operator fun get(index: Index): Move? = dictionary[index]?.move

    operator fun set(index: Index, value: Move?) {
        if (value != null) {
            add(move = value, toParentIndex = index.previous)
        }
    }

    /** Returns the previous index in the move tree based on [before]. If there is no previous index, [before] is returned. */
    fun indexBefore(before: Index): Index = previousIndex(before) ?: before

    /** Returns `true` if a valid index before [before] exists. */
    fun hasIndexBefore(before: Index): Boolean = previousIndex(before) != null

    private fun previousIndex(index: Index): Index? = if (index == minimumIndex.next) {
        minimumIndex
    } else {
        dictionary[index]?.previous?.index
    }

    /** Returns the next index in the move tree based on [after]. If there is no next index, [after] is returned. */
    fun indexAfter(after: Index): Index = nextIndex(after) ?: after

    /** Returns `true` if a valid index after [after] exists. */
    fun hasIndexAfter(after: Index): Boolean = nextIndex(after) != null

    private fun nextIndex(index: Index): Index? = if (index == minimumIndex) {
        dictionary[minimumIndex.next]?.index
    } else {
        dictionary[index]?.next?.index
    }

    override fun equals(other: Any?): Boolean {
        if (other !is MoveTree) return false
        return dictionary == other.dictionary
    }

    override fun hashCode(): Int = dictionary.hashCode()

    /** Object that represents the index of a node in the move tree. */
    data class Index(
        /** The move number. */
        val number: Int,
        /** The color of the piece moved on this move. */
        val color: Piece.Color,
        /**
         * The variation number of the move.
         *
         * If multiple moves occur for the same move number and piece color, the
         * [variation] is incremented.
         *
         * A [variation] equal to [mainVariation] is assumed to be the main variation in a move tree.
         */
        val variation: Int = mainVariation,
    ) : Comparable<Index> {

        /**
         * The previous index.
         *
         * This assumes [variation] is constant. For the previous index taking into
         * account variations use [MoveTree.index] with `before`.
         */
        val previous: Index
            get() = when (color) {
                Piece.Color.white -> Index(number - 1, Piece.Color.black, variation)
                Piece.Color.black -> Index(number, Piece.Color.white, variation)
            }

        /**
         * The next index.
         *
         * This assumes [variation] is constant. For the next index taking into account
         * variations use [MoveTree.index] with `after`.
         */
        val next: Index
            get() = when (color) {
                Piece.Color.white -> Index(number, Piece.Color.black, variation)
                Piece.Color.black -> Index(number + 1, Piece.Color.white, variation)
            }

        override fun compareTo(other: Index): Int = when {
            variation == other.variation -> when {
                number == other.number -> when {
                    color == Piece.Color.white && other.color == Piece.Color.black -> -1
                    color == other.color -> 0
                    else -> 1
                }
                else -> number.compareTo(other.number)
            }
            // prioritize lower variation numbers (since 0 is the main variation)
            else -> other.variation.compareTo(variation)
        }

        companion object {
            /** Variation number corresponding to the main variation of the tree. */
            const val mainVariation = 0

            /**
             * The minimum value, `MoveTree.Index(number = 0, color = black)`.
             *
             * This represents the starting position of the game.
             *
             * i.e. `MoveTree.Index(number = 1, color = white)` is returned by
             * [minimum]'s [next], which is the first move of the game (played by white).
             */
            val minimum = Index(0, Piece.Color.black)
        }
    }

    /** Object that represents a node in the move tree. Internal implementation detail of [MoveTree]. */
    internal class Node(move: Move) {
        var move: Move = move
        var positionAssessment: Position.Assessment = Position.Assessment.`null`
        var index: Index = Index.minimum
        var previous: Node? = null
        var next: Node? = null
        val children: MutableList<Node> = mutableListOf()

        override fun equals(other: Any?): Boolean = other is Node && index == other.index && move == other.move
        override fun hashCode(): Int = 31 * index.hashCode() + move.hashCode()
    }
}
