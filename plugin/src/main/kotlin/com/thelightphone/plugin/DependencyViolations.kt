package com.thelightphone.plugin

/**
 * Collects dependency violations, reporting each one once with every
 * configuration it was found in.
 */
class DependencyViolations {
    private val configurations = linkedMapOf<String, MutableSet<String>>()

    fun add(configuration: String, violation: String) {
        configurations.getOrPut(violation) { sortedSetOf() }.add(configuration)
    }

    fun lines(): List<String> =
        configurations.map { (violation, configs) -> "  $violation (in ${configs.joinToString()})" }
}
