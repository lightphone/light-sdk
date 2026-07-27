package com.thelightphone.sdk.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightServiceMethodTest {

    private val allDeclared: List<LightServiceMethod<*, *>> by lazy {
        LightServiceMethod::class.java.declaredClasses
            .filter { LightServiceMethod::class.java.isAssignableFrom(it) }
            .mapNotNull { clazz ->
                runCatching {
                    clazz.getDeclaredField("INSTANCE").get(null) as LightServiceMethod<*, *>
                }.getOrNull()
            }
    }

    @Test
    fun noDuplicateIds() {
        val duplicates = allDeclared
            .groupBy { it.id }
            .filter { it.value.size > 1 }
            .keys
        assertTrue(duplicates.isEmpty(), "Duplicate LightServiceMethod ids: $duplicates")
    }

    @Test
    fun allMethodsIsComplete() {
        val missing = allDeclared.filter { it.id !in allMethods }
        assertTrue(missing.isEmpty(), "Methods missing from allMethods: ${missing.map { it.id }}")
    }

    @Test
    fun allMethodsHasNoExtras() {
        val declared = allDeclared.map { it.id }.toSet()
        val extras = allMethods.keys.filter { it !in declared }
        assertEquals(emptyList(), extras, "allMethods contains unknown ids: $extras")
    }
}
