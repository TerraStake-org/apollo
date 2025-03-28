package io.muun.apollo.data.apis

import com.google.android.gms.auth.UserRecoverableAuthException
import io.muun.common.utils.ExceptionUtils

/**
 * Wrapper exception for Google Drive related errors.
 * 
 * @property cause The underlying exception that caused this error
 * @constructor Creates a DriveError with the specified cause
 */
class DriveError(
    cause: Throwable
) : RuntimeException("Google Drive operation failed", cause) {

    /**
     * Checks if this error was caused by missing permissions.
     * @return true if the error contains a [UserRecoverableAuthException], false otherwise
     */
    fun isMissingPermissions(): Boolean =
        ExceptionUtils.getTypedCause(this, UserRecoverableAuthException::class.java).isPresent

    companion object {
        /**
         * Creates a [DriveError] from a nullable throwable.
         * @param throwable The nullable throwable to wrap
         * @return DriveError if throwable was non-null, null otherwise
         */
        fun fromNullable(throwable: Throwable?): DriveError? =
            throwable?.let { DriveError(it) }
    }
}