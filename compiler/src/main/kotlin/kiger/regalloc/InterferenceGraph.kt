package kiger.regalloc

import kiger.regalloc.InterferenceGraph.INode
import kiger.temp.Temp
import java.util.*
import java.util.Objects.hash

class InterferenceGraph(temps: Set<Temp>) {

    val nodes = temps.mapIndexed { i, temp -> INode(i, temp) }
    val moves = mutableListOf<Move>()

    private val adjSet = AdjacencySet(nodes.size)

    fun addMove(s: Temp, d: Temp) {
        val src = this[s]
        val dst = this[d]
        val move = Move(src, dst)

        src.moveList += move
        dst.moveList += move

        moves += move
    }

    fun addEdge(u: Temp, v: Temp) {
        addEdge(this[u], this[v])
    }

    fun addEdge(u: INode, v: INode) {
        if (!contains(u, v) && u != v) {
            adjSet.addEdge(v, u)
            adjSet.addEdge(u, v)

            if (!u.precolored) {
                u.adjList += v
                u.degree += 1
            }

            if (!v.precolored) {
                v.adjList += u
                v.degree += 1
            }
        }
    }

    fun contains(u: INode, v: INode) = adjSet.contains(u, v)

    operator fun get(t: Temp): INode =
        nodes.find { it.temp == t } ?: error("could not find node for $t")

    @Suppress("unused")
    fun check() {
        val precolored = nodes.filter { it.precolored }
        check(precolored.all { it.adjList.isEmpty() }) { "precolored nodes with adj-lists" }
        check(precolored.all { v -> precolored.all { u -> v == u || contains(u, v) }}) { "no edges for precolored" }
        check(nodes.all { v -> v.adjList.all { u -> contains(v,u) && contains(u, v) }}) { "no set for adjList item" }

        check(nodes.all { it !in it.adjList}) { "node is in its own adjacency set"}
        check(nodes.all { !contains(it, it) }) { "self-edge on adjacency set" }
    }

    override fun toString(): String {
        val nodes = nodes.sortedBy { if (it.precolored) "z${it.temp.name}" else it.temp.name }

        // dump interference graph but don't include precolored <-> precolored edges
        val sb = StringBuilder()
        for (row in nodes) {
            sb.append(row.temp.name.padStart(10) + " [${row.degree}/${row.adjList.size}]: ")
            for (col in nodes) {
                if (contains(row, col) && (!row.precolored || !col.precolored))
                    sb.append("${col.temp} ")
            }
            sb.appendln()
        }

        return sb.toString()
    }

    class INode(val id: Int, val temp: Temp) {

        val adjList = mutableSetOf<INode>()
        var degree = 0
            get() = field
            set(v) {
                check(!precolored)
                field = v
            }

        var precolored = false

        /** Mapping from node to moves it's associated with */
        val moveList = mutableListOf<Move>()

        /** When move `(u,v)` has been coalesced, and `v` is put in coalescedNodes, then `alias(v) == u` */
        var alias: INode? = null

        override fun toString() = temp.name
    }

    class Move(val src: INode, val dst: INode) {
        override fun toString() = "${src.temp} -> ${dst.temp}"
        override fun equals(other: Any?) = other is Move && src == other.src && dst == other.dst
        override fun hashCode() = hash(src, dst)
    }
}

private class AdjacencySet(private val size: Int) {
    private val bitset = BitSet(size * size)

    fun addEdge(u: INode, v: INode) {
        bitset.set(bit(u, v))
    }

    fun contains(u: INode, v: INode) =
        bitset.get(bit(u, v))

    private fun bit(u: INode, v: INode) = u.id * size + v.id
}
