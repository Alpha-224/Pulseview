package com.Twinhealth.pulseview

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Manager class responsible for handling Firebase Authentication combined with the
 * Android Credential Manager API for Google Sign-In.
 */
class AuthManager {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    /**
     * Checks if there is currently a signed-in Firebase user.
     * Note: This does not re-verify the email domain. Callers should verify the email domain on app launch.
     */
    fun getCurrentUser(): FirebaseUser? {
        return auth.currentUser
    }

    /**
     * Signs out the current user from Firebase Auth.
     */
    fun signOut() {
        auth.signOut()
    }

    /**
     * Launches the Credential Manager Google Sign-In flow,
     * exchanges the resulting Google ID token for a Firebase credential, and returns the user.
     *
     * KDoc:
     * NOTE ON SECURITY BOUNDARY:
     * This client-side check verifying that the email address ends with "@Twinhealth.com" is purely
     * for UX convenience to guide users away from logging in with personal accounts. It is NOT the
     * actual security boundary. A compromised or modified APK could easily bypass client-side checks.
     * The true security boundary MUST be enforced server-side via Firestore Security Rules, which verify
     * that the request.auth.token.email matches the "@Twinhealth.com" domain format.
     */
    suspend fun signInWithGoogle(context: Context): Result<FirebaseUser> {
        return try {
            val credentialManager = CredentialManager.create(context)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId("345327189094-rk2h1gavh3ff07ml65dbaqtb6sb61t8v.apps.googleusercontent.com")
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response = credentialManager.getCredential(
                context = context,
                request = request
            )

            val credential = response.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(firebaseCredential).await()
                val firebaseUser = authResult.user ?: throw Exception("Firebase user is null")

                // Client-side domain check
                val email = firebaseUser.email
                if (email == null || !email.endsWith("@Twinhealth.com", ignoreCase = true)) {
                    auth.signOut()
                    return Result.failure(SecurityException("Access restricted to Twinhealth.com accounts"))
                }

                Result.success(firebaseUser)
            } else {
                Result.failure(Exception("Unexpected credential type returned from Credential Manager"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
