package com.thelightphone.authenticator

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthenticatorViewModel(
    private val repository: TotpAccountRepository,
) : LightViewModel<Unit>() {
    private val _accounts = MutableStateFlow<List<StoredAccount>>(emptyList())
    val accounts: StateFlow<List<StoredAccount>> = _accounts.asStateFlow()

    private val _errorModal = MutableStateFlow<String?>(null)
    val errorModal: StateFlow<String?> = _errorModal.asStateFlow()

    init {
        reloadAccounts()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        reloadAccounts()
    }

    fun showError(message: String) {
        _errorModal.value = message
    }

    fun dismissError() {
        _errorModal.value = null
    }

    private fun reloadAccounts() {
        viewModelScope.launch(Dispatchers.IO) {
            _accounts.value = repository.listAccounts()
        }
    }
}
