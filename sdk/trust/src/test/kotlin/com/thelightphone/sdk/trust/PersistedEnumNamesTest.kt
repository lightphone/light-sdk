package com.thelightphone.sdk.trust

import com.thelightphone.sdk.shared.ToolFilterLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [FileAdmissionPersistence] persists these two enums by name (`.name` on write, `.valueOf()` on
 * read) - renaming or removing a constant would silently fail to read back old records rather than
 * failing at compile time. This is a tripwire, not a structural fix: it forces a conscious update
 * here (and a think-through of what that means for already-written files) whenever one of these
 * enums changes, rather than the break only surfacing later as a runtime read failure.
 *
 * [LightInstallDecision.Deny]'s [DenyReason] is not included: it is never persisted by
 * [FileAdmissionPersistence] (only surfaced transiently in evaluation results), so a rename there
 * doesn't carry this risk.
 */
class PersistedEnumNamesTest {
    @Test
    fun `ToolFilterLevel constant names are unchanged`() {
        assertEquals(
            setOf(
                "ExcludeAllApks",
                "AllowLightApprovedApks",
                "AllowLightSignedApks",
                "AllowAllApks",
            ),
            ToolFilterLevel.entries.map { it.name }.toSet(),
        )
    }

    /** Hosts persist the filter level by ordinal, so declaration order is part of the format. */
    @Test
    fun `ToolFilterLevel declaration order is unchanged`() {
        assertEquals(
            listOf(
                "ExcludeAllApks",
                "AllowLightApprovedApks",
                "AllowLightSignedApks",
                "AllowAllApks",
            ),
            ToolFilterLevel.entries.map { it.name },
        )
    }

    @Test
    fun `AdmissionException constant names are unchanged`() {
        assertEquals(
            setOf("AllowAll", "DeveloperHash", "EmulatorDevelopmentKey"),
            AdmissionException.entries.map { it.name }.toSet(),
        )
    }
}
