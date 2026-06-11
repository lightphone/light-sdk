package com.thelightphone.authenticator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.File

@InitialScreen
class AuthenticatorHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<AuthenticatorViewModel>(sealedActivity) {

    private val repository = TotpAccountRepository.getInstance(
        databaseFile = File(filesDir, TotpAccountRepository.DATABASE_FILE_NAME),
    )

    override val viewModelClass: Class<AuthenticatorViewModel>
        get() = AuthenticatorViewModel::class.java

    override val showBackBar: Boolean = false

    override fun createViewModel() = AuthenticatorViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val accounts by viewModel.accounts.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Authenticator"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                if (accounts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "no accounts added",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                    }
                } else {
                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        accounts.forEach { account ->
                            LightText(
                                text = account.displayName,
                                variant = LightTextVariant.Copy,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        AuthenticatorCodeNavigation.open(account.id)
                                        navigateTo(::AuthenticatorCodeScreen)
                                    }
                                    .padding(vertical = 0.75f.gridUnitsAsDp()),
                            )
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "ADD NEW",
                            onClick = { navigateTo(::AuthenticatorQrScannerScreen) },
                        ),
                    ),
                )
            }
        }
    }
}
