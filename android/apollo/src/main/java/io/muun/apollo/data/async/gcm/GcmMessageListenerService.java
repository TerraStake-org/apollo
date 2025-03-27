package io.muun.apollo.data.async.gcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.muun.apollo.data.db.DaoManager
import io.muun.apollo.data.external.DataComponentProvider
import io.muun.apollo.data.net.HoustonClient
import io.muun.apollo.data.net.ModelObjectsMapper
import io.muun.apollo.data.os.execution.ExecutionTransformerFactory
import io.muun.apollo.data.serialization.SerializationUtils
import io.muun.apollo.domain.LoggingContextManager
import io.muun.apollo.domain.action.NotificationActions
import io.muun.apollo.domain.action.fcm.UpdateFcmTokenAction
import io.muun.apollo.domain.errors.fcm.FcmMessageProcessingError
import io.muun.apollo.domain.model.NotificationReport
import io.muun.common.api.beam.notification.NotificationReportJson
import timber.log.Timber
import javax.inject.Inject

class GcmMessageListenerService : FirebaseMessagingService() {

    @Inject lateinit var loggingContextManager: LoggingContextManager
    @Inject lateinit var daoManager: DaoManager
    @Inject lateinit var houstonClient: HoustonClient
    @Inject lateinit var updateFcmTokenAction: UpdateFcmTokenAction
    @Inject lateinit var executionTransformerFactory: ExecutionTransformerFactory
    @Inject lateinit var mapper: ModelObjectsMapper
    @Inject lateinit var notificationActions: NotificationActions

    override fun onCreate() {
        super.onCreate()
        injectDependencies()
        initializeService()
    }

    private fun injectDependencies() {
        try {
            (application as DataComponentProvider)
                .getDataComponent()
                .inject(this)
        } catch (e: Exception) {
            Timber.e(e, "Failed to inject dependencies")
            throw IllegalStateException("Dependency injection failed", e)
        }
    }

    private fun initializeService() {
        Timber.d("Starting GcmMessageListenerService")
        loggingContextManager.setupCrashlytics()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("Received new FCM token")
        handleNewToken(token)
    }

    private fun handleNewToken(token: String) {
        try {
            updateFcmTokenAction.run(token)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update FCM token")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val message = remoteMessage.data["message"] ?: run {
            Timber.w("Received message with empty payload")
            return
        }

        try {
            processNotificationMessage(message)
        } catch (e: Throwable) {
            Timber.e(FcmMessageProcessingError(remoteMessage, e))
        }
    }

    private fun processNotificationMessage(message: String) {
        val notificationJson = try {
            SerializationUtils.deserializeJson(NotificationReportJson::class.java, message)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid NotificationReportJson: $message", e)
        }

        val report = mapper.mapNotificationReport(notificationJson)
        notificationActions.onNotificationReport(report)
    }

    override fun onDestroy() {
        Timber.d("Destroying GcmMessageListenerService")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GcmMessageListener"
    }
}
