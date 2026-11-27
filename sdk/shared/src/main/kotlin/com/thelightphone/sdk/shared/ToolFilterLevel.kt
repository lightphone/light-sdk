package com.thelightphone.sdk.shared

/**
 * Which tools LightOS lets the user see and run, from strictest to most permissive.
 *
 * Persisted two ways, so neither may change for an existing constant: hosts store the level by
 * ordinal (settings, the LightOS JS bridge), and admission records store it by name. Append new
 * levels only at the end.
 */
enum class ToolFilterLevel {
    // LightOS will not show any tools other than the ones baked in by default
    ExcludeAllApks,

    // LightOS will show default tools as well as those curated from the community
    AllowLightApprovedApks,

    // LightOS will show default tools as well as any that have been built as SDK clients and signed
    // using a Light SDK cert
    AllowLightSignedApks,

    // LightOS will show default tools as well as any other APKs that have been installed on the
    // device.
    AllowAllApks,
}
