package com.hbe.graph

data class BuildGraph(
    val nodes: List<BuildNode> = emptyList(),
    val edges: List<BuildEdge> = emptyList()
) {
    fun validate(): List<String> {
        val issues = mutableListOf<String>()
        val nodeIds = nodes.map { it.id }.toSet()

        // Check for dangling edges
        for (edge in edges) {
            if (edge.from !in nodeIds) issues.add("Edge references non-existent source node: ${edge.from}")
            if (edge.to !in nodeIds) issues.add("Edge references non-existent target node: ${edge.to}")
        }

        // Check for cycles (DFS)
        val cycle = findCycle()
        if (cycle != null) issues.add("Cycle detected: ${cycle.joinToString(" -> ")}")

        return issues
    }

    fun topologicalSort(): List<BuildNode> {
        val inDegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()

        for (node in nodes) {
            inDegree[node.id] = 0
            adjacency[node.id] = mutableListOf()
        }

        for (edge in edges) {
            adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
            inDegree[edge.to] = (inDegree[edge.to] ?: 0) + 1
        }

        val queue = ArrayDeque<String>()
        for ((id, degree) in inDegree) {
            if (degree == 0) queue.add(id)
        }

        val sorted = mutableListOf<BuildNode>()
        val nodeMap = nodes.associateBy { it.id }

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            nodeMap[id]?.let { sorted.add(it) }
            for (neighbor in adjacency[id].orEmpty()) {
                inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                if (inDegree[neighbor] == 0) queue.add(neighbor)
            }
        }

        if (sorted.size != nodes.size) {
            throw IllegalStateException("Graph contains a cycle — cannot topological sort")
        }

        return sorted
    }

    /** Topological levels: each level contains nodes whose dependencies are all in earlier levels.
     *  Nodes within a level are mutually independent and may run in parallel. */
    fun levels(): List<List<BuildNode>> {
        val adjacency = mutableMapOf<String, MutableList<String>>()
        val inDegree = mutableMapOf<String, Int>()
        for (node in nodes) {
            adjacency[node.id] = mutableListOf()
            inDegree[node.id] = 0
        }
        for (edge in edges) {
            adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
            inDegree[edge.to] = (inDegree[edge.to] ?: 0) + 1
        }

        val nodeMap = nodes.associateBy { it.id }
        val ready = java.util.ArrayDeque<String>()
        for ((id, degree) in inDegree) if (degree == 0) ready.add(id)

        val levels = mutableListOf<List<BuildNode>>()
        val visited = mutableSetOf<String>()
        while (ready.isNotEmpty()) {
            val level = mutableListOf<BuildNode>()
            val next = mutableListOf<String>()
            while (ready.isNotEmpty()) {
                val id = ready.poll()
                if (id in visited) continue
                visited.add(id)
                nodeMap[id]?.let { level.add(it) }
                for (neighbor in adjacency[id].orEmpty()) {
                    inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                    if (inDegree[neighbor] == 0) next.add(neighbor)
                }
            }
            levels.add(level)
            ready.addAll(next)
        }

        if (visited.size != nodes.size) {
            throw IllegalStateException("Graph contains a cycle — cannot compute levels")
        }
        return levels
    }

    /** Renders the dependency graph as an indented text tree. */
    fun visualize(): String {
        val sorted = topologicalSort()
        val byId = nodes.associateBy { it.id }
        val children = mutableMapOf<String, MutableList<String>>()
        val parents = mutableMapOf<String, MutableList<String>>()
        for (node in nodes) {
            children[node.id] = mutableListOf()
            parents[node.id] = mutableListOf()
        }
        for (edge in edges) {
            children.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
            parents.getOrPut(edge.to) { mutableListOf() }.add(edge.from)
        }

        val roots = sorted.map { it.id }.filter { parents[it].orEmpty().isEmpty() }
        val sb = StringBuilder()
        sb.append("build-graph\n")

        fun render(nodeId: String, depth: Int) {
            val node = byId[nodeId] ?: return
            val indent = if (depth == 0) "  " else "  " + "  ".repeat(depth)
            val marker = if (depth == 0) "|- " else "`-> "
            sb.append(indent).append(marker).append(node.label.ifBlank { node.id })
                .append(" [").append(node.type.name).append("]\n")
            for (child in children[nodeId].orEmpty().sorted()) {
                render(child, depth + 1)
            }
        }

        for (root in roots.sorted()) render(root, 0)
        return sb.toString()
    }

    fun findCycle(): List<String>? {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val parent = mutableMapOf<String, String>()
        val adjacency = mutableMapOf<String, List<String>>()

        for (node in nodes) adjacency[node.id] = edges.filter { it.from == node.id }.map { it.to }

        fun dfs(node: String, path: MutableList<String>): List<String>? {
            visiting.add(node)
            path.add(node)

            for (neighbor in adjacency[node].orEmpty()) {
                if (neighbor in visiting) {
                    val cycleStart = path.indexOf(neighbor)
                    return path.subList(cycleStart, path.size) + neighbor
                }
                if (neighbor !in visited) {
                    val result = dfs(neighbor, path)
                    if (result != null) return result
                }
            }

            path.removeAt(path.size - 1)
            visiting.remove(node)
            visited.add(node)
            return null
        }

        for (node in nodes) {
            if (node.id !in visited) {
                val result = dfs(node.id, mutableListOf())
                if (result != null) return result
            }
        }

        return null
    }

    companion object {
        fun empty() = BuildGraph()
    }
}

data class BuildNode(
    val id: String,
    val type: NodeType,
    val label: String = "",
    val estimatedMemoryMb: Long = 256,
    val tags: Set<String> = emptySet()
)

enum class NodeType {
    SDK_RESOLVE,
    DEP_RESOLVE,
    MANIFEST_MERGE,
    RES_COMPILE,
    RES_LINK,
    SOURCE_COMPILE,
    DEX,
    R8,
    PACKAGE,
    SIGN,
    ALIGN
}

data class BuildEdge(
    val from: String,
    val to: String,
    val type: EdgeType = EdgeType.DEPENDS_ON
)

enum class EdgeType {
    DEPENDS_ON,
    PRODUCES,
    CONSUMES
}
