package com.projectx.traversal

class TraversalNodeList(start: TraversalNode) {
    val head: TraversalNode = start
    var curr: TraversalNode = start

    fun add(node: TraversalNode) = apply {
        curr.next = node
        node.prev = curr
        curr = node
    }

    fun copy(): TraversalNodeList {
        return TraversalNodeList(head.copy().also { it.copyRouteFlagsFrom(head) }).also { newList ->
            var originalNode = head
            var currentNode = newList.head
            while (originalNode.next != null) {
                val original = originalNode.next!!
                val nextCopy = original.copy().also { it.copyRouteFlagsFrom(original) }
                currentNode.next = nextCopy
                nextCopy.prev = currentNode

                originalNode = original
                currentNode = nextCopy
            }
            var newCurrent = newList.head
            var origCurrent = head
            while (origCurrent !== curr && newCurrent.next != null) {
                newCurrent = newCurrent.next!!
                origCurrent = origCurrent.next!!
            }
            newList.curr = newCurrent
        }
    }
}
