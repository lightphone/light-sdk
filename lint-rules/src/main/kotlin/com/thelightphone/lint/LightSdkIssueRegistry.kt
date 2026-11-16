package com.thelightphone.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class LightSdkIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(
        ActivityAccessDetector.ISSUE_LOCAL_CONTEXT,
        ActivityAccessDetector.ISSUE_LOCAL_VIEW,
        ActivityAccessDetector.ISSUE_ACTIVITY_CAST,
        LightJobDetector.ISSUE_LIGHT_JOB_INVALID,
        LightJobDetector.ISSUE_LIGHT_JOB_EMPTY_KEY,
        LightAlertDismissDetector.ISSUE_ALERT_NEVER_DISMISSED,
    )

    override val api: Int = CURRENT_API

    override val vendor: Vendor = Vendor(
        vendorName = "The Light Phone",
        identifier = "com.thelightphone.lint",
    )
}
