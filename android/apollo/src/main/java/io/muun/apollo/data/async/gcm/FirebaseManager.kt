package io.muun.apollo.data.async.gcm

import com.google.firebase.messaging.FirebaseMessaging
import io.muun.apollo.domain.action.fcm.UpdateFcmTokenAction
import io.muun.apollo.domain.errors.MuunError
import io.muun.apollo.domain.errors.fcm.FcmTokenCanceledError
import io.muun.apollo.domain.errors.fcm.FcmTokenError
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseManager @Inject constructor(
    private val updateFcmTokenAction: UpdateFcmTokenAction,
    private val ioExecutor: Executor
) {

    private val tokenSubject = BehaviorSubject.create<String>()

    /**
     * Manually fetch FCM token. Useful for force-fetching when hasn't arrived via
     * FirebaseMessagingService#onNewToken.
     * 
     * @return Observable that emits the FCM token or an error
     */
    fun fetchFcmToken(): Observable<String> {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            when {
                task.isCanceled -> {
                    val error = FcmTokenCanceledError()
                    Timber.e(error)
                    tokenSubject.onError(error)
                }
                !task.isSuccessful -> {
                    val error = FcmTokenError(
                        task.exception ?: RuntimeException("Unknown FCM token error")
                    )
                    Timber.e(error)
                    tokenSubject.onError(error)
                }
                else -> {
                    val token = requireNotNull(task.result) { "FCM token was null despite successful task" }
                    Timber.i("FCM token fetch SUCCESS: $token")
                    updateFcmToken(token)
                    tokenSubject.onNext(token)
                }
            }
        }

        return tokenSubject.hide() // Prevent subject errors from terminating the observable
    }

    /**
     * Delete FCM Token, forcing a new token generation which should trigger
     * FirebaseMessagingService#onNewToken.
     */
    fun resetFcmToken() {
        ioExecutor.execute {
            try {
                FirebaseMessaging.getInstance().deleteToken()
                Timber.i("FCM token successfully deleted")
            } catch (e: IOException) {
                Timber.e(FcmTokenError(e))
            } catch (e: Exception) {
                Timber.e(FcmTokenError(e))
            }
        }
    }

    private fun updateFcmToken(token: String) {
        try {
            updateFcmTokenAction.run(token)
        } catch (e: Exception) {
            Timber.e(FcmTokenError(e))
        }
    }
}
