package com.hbe.graph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BuildGraphTest {

    @Test
    fun `empty graph validates successfully`() {
        val graph = BuildGraph.empty()
        val issues = graph.validate()
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `topological sort of linear graph`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("a", NodeType.SDK_RESOLVE),
                BuildNode("b", NodeType.DEP_RESOLVE),
                BuildNode("c", NodeType.SOURCE_COMPILE)
            ),
            edges = listOf(
                BuildEdge("a", "b"),
                BuildEdge("b", "c")
            )
        )

        val sorted = graph.topologicalSort()
        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
    }

    @Test
    fun `detects cycle in graph`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("a", NodeType.SDK_RESOLVE),
                BuildNode("b", NodeType.DEP_RESOLVE),
                BuildNode("c", NodeType.SOURCE_COMPILE)
            ),
            edges = listOf(
                BuildEdge("a", "b"),
                BuildEdge("b", "c"),
                BuildEdge("c", "a")
            )
        )

        val cycle = graph.findCycle()
        assertNotNull(cycle)
        assertTrue(cycle!!.isNotEmpty())
    }

    @Test
    fun `validation detects dangling edges`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("a", NodeType.SDK_RESOLVE)
            ),
            edges = listOf(
                BuildEdge("a", "nonexistent")
            )
        )

        val issues = graph.validate()
        assertTrue(issues.any { it.contains("nonexistent") })
    }

    @Test
    fun `levels splits diamond graph into independent levels`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("root", NodeType.SDK_RESOLVE, label = "ROOT"),
                BuildNode("left", NodeType.RES_COMPILE, label = "LEFT"),
                BuildNode("right", NodeType.SOURCE_COMPILE, label = "RIGHT"),
                BuildNode("leaf", NodeType.PACKAGE, label = "LEAF")
            ),
            edges = listOf(
                BuildEdge("root", "left"),
                BuildEdge("root", "right"),
                BuildEdge("left", "leaf"),
                BuildEdge("right", "leaf")
            )
        )

        val levels = graph.levels()

        assertEquals(3, levels.size)
        assertEquals(listOf("root"), levels[0].map { it.id })
        assertEquals(setOf("left", "right"), levels[1].map { it.id }.toSet())
        assertEquals(listOf("leaf"), levels[2].map { it.id })
    }

    @Test
    fun `levels throws on cyclic graph`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("a", NodeType.SDK_RESOLVE),
                BuildNode("b", NodeType.DEP_RESOLVE)
            ),
            edges = listOf(BuildEdge("a", "b"), BuildEdge("b", "a"))
        )

        assertThrows(IllegalStateException::class.java) { graph.levels() }
    }

    @Test
    fun `visualize renders text tree with node ids`() {
        val graph = BuildGraph(
            nodes = listOf(
                BuildNode("a", NodeType.SDK_RESOLVE, label = "SDK"),
                BuildNode("b", NodeType.SOURCE_COMPILE, label = "COMPILE")
            ),
            edges = listOf(BuildEdge("a", "b"))
        )

        val viz = graph.visualize()
        assertTrue(viz.contains("SDK [SDK_RESOLVE]"))
        assertTrue(viz.contains("COMPILE [SOURCE_COMPILE]"))
        assertTrue(viz.contains("->"))
    }
}
