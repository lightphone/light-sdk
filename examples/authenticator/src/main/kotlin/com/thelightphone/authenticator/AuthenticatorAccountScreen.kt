package com.thelightphone.authenticator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthenticatorAccountScreen(
    sealedActivity: SealedLightActivity,
    private val accountResult: Result<StoredAccount>,
    private val repository: TotpAccountRepository
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val account = remember { accountResult.getOrNull() }
        val parseError = remember { accountResult.exceptionOrNull() }

        var secret by remember(account?.id) { mutableStateOf<String?>(null) }

        LaunchedEffect(account?.id) {
            val id = account?.id ?: return@LaunchedEffect
            secret = withContext(Dispatchers.IO) {
                repository.decryptSecret(id)
            }
        }

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
                    ),
                    center = LightTopBarCenter.Text("Account"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    when {
                        account != null -> {
                            AccountDetails(account)
                            val loadedSecret = secret
                            if (loadedSecret != null) {
                                TotpCodeDisplay(
                                    issuer = account.issuer,
                                    label = account.label,
                                    secret = loadedSecret,
                                    digits = account.digits,
                                    period = account.period,
                                    algorithm = account.algorithm,
                                )
                            }
                        }

                        parseError != null -> {
                            LightText(
                                text = parseError.message ?: "Invalid QR Code",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                            )
                        }

                        else -> {
                            LightText(
                                text = "(no account data)",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountDetails(account: StoredAccount) {
    AccountField(label = "Issuer", value = account.issuer.ifBlank { "(empty)" })
    AccountField(label = "Label", value = account.label.ifBlank { "(empty)" })
    AccountField(label = "Secret", value = "Stored securely")
    AccountField(label = "Digits", value = account.digits.toString())
    AccountField(label = "Period", value = "${account.period}s")
    AccountField(label = "Algorithm", value = account.algorithm)
}

@Composable
private fun AccountField(label: String, value: String) {
    LightText(
        text = label.uppercase(),
        variant = LightTextVariant.Fine,
        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
    )
    LightText(
        text = value,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
    )
}
