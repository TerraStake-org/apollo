package io.muun.apollo.data.async.tasks

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import io.muun.apollo.data.external.NotificationService
import io.muun.apollo.domain.errors.LnUrlWithdrawParseError
import io.muun.apollo.domain.model.LnUrlWithdraw
import timber.log.Timber
import javax.inject.Inject

class LnPaymentFailedNotificationWorker @Inject constructor(
    context: Context,
    params: WorkerParameters,
    private val notificationService: NotificationService
) : CoroutineWorker(context, params) {

    companion object {
        const val LNURL_WITHDRAW_KEY = "lnUrlWithdraw"
        
        fun createInputData(lnUrlWithdraw: LnUrlWithdraw): Data {
            return Data.Builder()
                .putString(LNURL_WITHDRAW_KEY, lnUrlWithdraw.serialize())
                .build()
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val lnUrlWithdraw = parseLnUrlWithdraw()
            Timber.d("Showing LN payment expired notification")
            notificationService.showLnPaymentExpiredNotification(lnUrlWithdraw)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Failed to process LN payment failed notification")
            Result.failure()
        }
    }

    private fun parseLnUrlWithdraw(): LnUrlWithdraw {
        val serialized = inputData.getString(LNURL_WITHDRAW_KEY)
            ?: throw IllegalArgumentException("Missing LNURL withdraw data in input")

        return try {
            LnUrlWithdraw.deserialize(serialized)
        } catch (e: Exception) {
            throw LnUrlWithdrawParseError(serialized, e)
        }
    }
}
