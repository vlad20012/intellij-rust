/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.stdext

inline class Stack<E>(private val list: MutableList<E> = mutableListOf()): Iterable<E> {
    fun push(element: E) {
        list.add(element)
    }

    fun pop(): E = list.removeLast()

    /** Retrieves, but does not remove, the head of the stack */
    fun peek(): E = list.last()

    operator fun get(index: Int): E = list[index]
    operator fun set(index: Int, element: E): E = list.set(index, element)

    val size: Int get() = list.size

    fun isEmpty(): Boolean = list.isEmpty()
    fun isNotEmpty(): Boolean = list.isNotEmpty()

    override fun iterator(): Iterator<E> = list.iterator()
}
