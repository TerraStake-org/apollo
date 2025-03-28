package io.muun.apollo.data.apis

import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException

/**
 * Interface for Google Drive authentication operations.
 * Provides methods for signing in, retrieving account information, and signing out.
 * 
 * @throws ApiException When there's an error during Google Sign-In operations
 * @throws SecurityException When permissions are insufficient
 * @throws IllegalStateException When called from invalid contexts
 */
interface DriveAuthenticator {

    /**
     * Creates an Intent to start the Google Sign-In flow.
     * @return Intent configured for Google Sign-In
     */
    fun getSignInIntent(): Intent

    /**
     * Retrieves the signed-in account from the sign-in result Intent.
     * @param resultIntent The result Intent from the sign-in flow
     * @return Authenticated GoogleSignInAccount
     * @throws ApiException if the sign-in failed
     * @throws IllegalArgumentException if resultIntent is null
     */
    @Throws(ApiException::class, IllegalArgumentException::class)
    fun getSignedInAccount(resultIntent: Intent?): GoogleSignInAccount

    /**
     * Signs out the currently authenticated user.
     * Clears any cached authentication tokens.
     */
    fun signOut()

    /**
     * Optional: Add this if your implementation supports it
     * Checks if there's a currently signed-in account.
     * @return true if a user is currently signed in, false otherwise
     */
    fun isSignedIn(): Boolean
}