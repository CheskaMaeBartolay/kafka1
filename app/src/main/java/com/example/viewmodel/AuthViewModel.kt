package com.example.viewmodel

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.KafkaRepository
import com.example.model.UserProfile
import com.example.util.DeviceUtils
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Authenticated(val user: FirebaseUser, val profile: UserProfile?) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

class AuthViewModel(
    private val repository: KafkaRepository
) : ViewModel() {

    private val auth = Firebase.auth
    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _selectedMode = MutableStateFlow<String?>(null)
    val selectedMode: StateFlow<String?> = _selectedMode.asStateFlow()

    init {
        val current = auth.currentUser
        if (current != null) {
            loadUserProfile(current)
        }
    }

    fun initMode(context: Context) {
        _selectedMode.value = DeviceUtils.getSavedMode(context)
    }

    fun selectMode(context: Context, mode: String) {
        DeviceUtils.saveMode(context, mode)
        _selectedMode.value = mode
    }

    fun attemptSilentSignIn(context: Context) {
        if (auth.currentUser != null) {
            loadUserProfile(auth.currentUser!!)
            return
        }

        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            return
        }

        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        viewModelScope.launch {
            try {
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    val authResult = auth.signInWithCredential(authCredential).await()
                    authResult.user?.let { loadUserProfile(it) }
                }
            } catch (e: Exception) {
                Log.d("AuthViewModel", "Silent sign-in not available: ${e.message}")
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        val clientId = try {
            activity.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            _uiState.value = AuthUiState.Error("Google Client ID configuration missing")
            return
        }

        _uiState.value = AuthUiState.Loading
        val credentialManager = CredentialManager.create(activity)
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        viewModelScope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    val authResult = auth.signInWithCredential(authCredential).await()
                    authResult.user?.let {
                        loadUserProfile(it)
                    } ?: run {
                        _uiState.value = AuthUiState.Error("Firebase user is null after sign-in")
                    }
                } else {
                    _uiState.value = AuthUiState.Error("Unexpected credential type received")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("AuthViewModel", "Google Sign-In dismissed: ${e.message}", e)
                _uiState.value = AuthUiState.Idle
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Sign-in error", e)
                _uiState.value = AuthUiState.Error(e.localizedMessage ?: "Sign-in failed")
            }
        }
    }

    private fun loadUserProfile(user: FirebaseUser) {
        viewModelScope.launch {
            try {
                val profile = repository.getOrCreateUserProfile(
                    email = user.email ?: "",
                    displayName = user.displayName ?: "Guardian"
                )
                _uiState.value = AuthUiState.Authenticated(user, profile)
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Authenticated(user, null)
            }
        }
    }

    fun signOut(context: Context) {
        val credentialManager = CredentialManager.create(context)
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w("AuthViewModel", "Failed to clear credential state: ${e.message}")
                } finally {
                    auth.signOut()
                    _uiState.value = AuthUiState.Idle
                }
            }
        }
    }
}
