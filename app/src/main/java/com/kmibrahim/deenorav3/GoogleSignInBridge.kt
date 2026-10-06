package com.kmibrahim.deenorav3

import android.app.Activity
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Native Google Sign-In bridge for WebView.
 * Phone er account picker khulbe (existing Gmail list soho),
 * ID token web ke JavascriptInterface diye pathabe.
 */
class GoogleSignInBridge(
    private val activity: Activity,
    private val onToken: (String?) -> Unit
) {

    // Firebase Console > Authentication > Sign-in method > Google
    // > Web SDK configuration theke "Web client ID" bosao
    private val WEB_CLIENT_ID = "212679814097-9rm5bijd4crrtcmgg2atn1ppcnd8lau8.apps.googleusercontent.com"

    @JavascriptInterface
    fun signIn() {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = CredentialManager.create(activity).getCredential(activity, request)
                val credential = GoogleIdTokenCredential.createFrom(result.credential.data)
                Log.d("GoogleSignIn", "Got ID token")
                onToken(credential.idToken)
            } catch (e: GetCredentialException) {
                Log.e("GoogleSignIn", "Sign-in failed", e)
                onToken(null)
            }
        }
    }

    @JavascriptInterface
    fun isAvailable(): Boolean = true
}
