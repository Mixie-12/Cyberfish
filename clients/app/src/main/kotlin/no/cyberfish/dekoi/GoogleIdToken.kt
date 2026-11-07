package no.cyberfish.dekoi

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

/** A Google ID token and the raw nonce whose SHA-256 it carries. */
class GoogleIdToken(
    val idToken: String,
    val rawNonce: String,
)

/**
 * Asks Android's Credential Manager for a Google ID token for the backend's
 * web client. Google receives the SHA-256 of a fresh nonce; Supabase receives
 * the nonce itself and checks it, so a captured token cannot be replayed.
 */
suspend fun requestGoogleIdToken(
    context: Context,
    webClientId: String,
): GoogleIdToken {
    val rawNonce = ByteArray(32).also(SecureRandom()::nextBytes).toHex()
    val hashedNonce = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray()).toHex()
    val option = GetSignInWithGoogleOption.Builder(webClientId).setNonce(hashedNonce).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = CredentialManager.create(context).getCredential(context, request).credential
    check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        "unexpected credential type"
    }
    return GoogleIdToken(GoogleIdTokenCredential.createFrom(credential.data).idToken, rawNonce)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
