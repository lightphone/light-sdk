package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** A legal document rendered by [LegalDocScreen]. Copy mirrors the iOS companion app. */
enum class LegalDoc(
    val barTitle: String,
    val eyebrow: String,
    val headline: String,
    val bullets: List<String>,
) {
    Terms(
        barTitle = "Terms",
        eyebrow = "TERMS OF SERVICE",
        headline = "A fair game needs clear rules.",
        bullets = listOf(
            "When you play flamingo chess, each move/draw offer/resign action you submit is sent to and stored on our servers. This allows you to later see the games you've played.",
            "Games and their moves are publicly accessible.",
            "Your profile name is public: your opponent sees it, and it appears on every game you play.",
            "Text you enter that other players can see — for example your profile name — must not contain your real name or other personal details, impersonate anyone, carry offensive, obscene or hateful language or advertising, or use names and trademarks that aren't yours. We may reset or remove anything that breaks these rules.",
            "We can't guarantee persistence for eternity. Though we don't intend to, be aware that games may be deleted at any time without prior notice.",
        ),
    ),
    Privacy(
        barTitle = "Privacy",
        eyebrow = "PRIVACY POLICY",
        headline = "Your privacy, our promise.",
        bullets = listOf(
            "None of your personal information is collected, shared or logged.",
            "Your interaction with this app is not tracked.",
        ),
    ),
}

/**
 * The tool's settings: the [ArchiveScreen], this install's [AccountScreen] name, board display,
 * and the legal docs.
 */
class InfoScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back to games",
                    ),
                    center = LightTopBarCenter.Text("Info"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    InfoRow(title = "Archive") {
                        navigateTo(screenFactory = { ArchiveScreen(it) })
                    }
                    InfoRow(title = "Account") {
                        navigateTo(screenFactory = { AccountScreen(it) })
                    }
                    InfoRow(title = "Board") {
                        navigateTo(screenFactory = { SettingsScreen(it) })
                    }
                    InfoRow(title = "Terms of Service") {
                        navigateTo(screenFactory = { LegalDocScreen(it, LegalDoc.Terms) })
                    }
                    InfoRow(title = "Privacy Policy") {
                        navigateTo(screenFactory = { LegalDocScreen(it, LegalDoc.Privacy) })
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, onClick: () -> Unit) {
    LightText(
        text = title,
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    )
}

/** Scrollable read-only view of a single [LegalDoc]. */
class LegalDocScreen(
    sealedActivity: SealedLightActivity,
    private val doc: LegalDoc,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back to info",
                    ),
                    center = LightTopBarCenter.Text(doc.barTitle),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = doc.eyebrow,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = doc.headline,
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(
                            top = 0.5f.gridUnitsAsDp(),
                            bottom = 1f.gridUnitsAsDp(),
                        ),
                    )
                    doc.bullets.forEach { bullet ->
                        LightText(
                            text = "— $bullet",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}
