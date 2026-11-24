package com.thelightphone.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod

/**
 * Flags a module that receives or schedules alerts but never ends one. Until a tool calls
 * LightAlert.dismiss() or LightNotifications.cancel(id), LightOS brings it back every time the
 * screen turns off. Only checks that one of those calls exists somewhere in the module.
 *
 * A module that overrides onAlert has the LightAlert, so only dismiss() counts there; otherwise a
 * cancel(id) behind a "cancel scheduled alert" button would hide a tool that never dismisses.
 */
class LightAlertDismissDetector : Detector(), SourceCodeScanner {

    companion object {
        private const val ENTRY_POINT_FQN = "com.thelightphone.sdk.LightEntryPoint"
        private const val ALERT_FQN = "com.thelightphone.sdk.LightAlert"
        private const val NOTIFICATIONS_FQN = "com.thelightphone.sdk.LightNotifications"

        val ISSUE_ALERT_NEVER_DISMISSED = Issue.create(
            id = "LightSdkAlertNeverDismissed",
            briefDescription = "Alerts are never dismissed",
            explanation = "Until an alert is dismissed, LightOS brings the tool back every time " +
                "the screen turns off, so the phone keeps opening into it. Call `alert.dismiss()` " +
                "on the LightAlert passed to `onAlert` once the user has dealt with it, or " +
                "`notifications.cancel(id)` if you no longer have the LightAlert.",
            category = Category.CORRECTNESS,
            priority = 7,
            severity = Severity.WARNING,
            implementation = Implementation(
                LightAlertDismissDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }

    private var onAlertLocation: Location? = null
    private var scheduleLocation: Location? = null
    private var dismissCalled = false
    private var cancelByIdCalled = false

    override fun beforeCheckEachProject(context: Context) {
        onAlertLocation = null
        scheduleLocation = null
        dismissCalled = false
        cancelByIdCalled = false
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitMethod(node: UMethod) {
                if (onAlertLocation != null || node.name != "onAlert") return
                val overridesEntryPoint = node.javaPsi.findSuperMethods().any {
                    it.containingClass?.qualifiedName == ENTRY_POINT_FQN
                }
                if (overridesEntryPoint) onAlertLocation = context.getNameLocation(node)
            }
        }
    }

    override fun getApplicableMethodNames(): List<String> = listOf("dismiss", "cancel", "schedule")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        when (method.containingClass?.qualifiedName) {
            ALERT_FQN -> if (method.name == "dismiss") dismissCalled = true
            NOTIFICATIONS_FQN -> when {
                // cancel() with no id clears the plain notification, not an alert.
                method.name == "cancel" && method.parameterList.parametersCount == 1 -> cancelByIdCalled = true
                method.name == "schedule" && scheduleLocation == null ->
                    scheduleLocation = context.getLocation(node)
            }
        }
    }

    override fun afterCheckEachProject(context: Context) {
        if (dismissCalled) return
        val onAlert = onAlertLocation
        if (onAlert != null) {
            context.report(
                ISSUE_ALERT_NEVER_DISMISSED,
                onAlert,
                "Alerts are never dismissed; call `alert.dismiss()` once the user has dealt " +
                    "with the alert, or LightOS keeps reopening the tool",
            )
            return
        }
        if (cancelByIdCalled) return
        val schedule = scheduleLocation ?: return
        context.report(
            ISSUE_ALERT_NEVER_DISMISSED,
            schedule,
            "Alerts are never dismissed; override `onAlert` and call `alert.dismiss()` once the " +
                "user has dealt with the alert (or `notifications.cancel(id)`), or LightOS keeps " +
                "reopening the tool",
        )
    }
}
