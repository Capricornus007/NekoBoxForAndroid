package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemMoveIndicesTest {
    @Test
    fun movesPreserveEveryItemAndOrderSlotInBothDirections() {
        data class Item(val id: Int, var order: Long)
        val orders = listOf(10L, 30L, 80L, 90L, 150L)
        for (from in orders.indices) {
            for (to in orders.indices) {
                val items = orders.mapIndexed { id, order -> Item(id, order) }.toMutableList()
                val first = items[from]
                var previousOrder = first.order
                val step = if (from < to) 1 else -1
                for (index in itemMoveIndices(from, to)) {
                    val next = items[index + step]
                    val order = next.order
                    next.order = previousOrder
                    previousOrder = order
                    items[index] = next
                }
                first.order = previousOrder
                items[to] = first

                val expected = orders.indices.toMutableList().apply { add(to, removeAt(from)) }
                assertEquals("move $from to $to", expected, items.map { it.id })
                assertEquals(orders, items.map { it.order })
            }
        }
    }
}
