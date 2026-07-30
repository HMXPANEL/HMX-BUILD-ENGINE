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
}
