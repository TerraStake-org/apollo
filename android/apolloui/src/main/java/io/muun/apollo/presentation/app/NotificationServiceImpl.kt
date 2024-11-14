package io.muun.apollo.presentation.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import io.muun.apollo.R
import io.muun.apollo.data.async.tasks.LnPaymentFailedNotificationWorker
import io.muun.apollo.data.external.Globals
import io.muun.apollo.data.external.NotificationService
import io.muun.apollo.data.os.execution.ExecutionTransformerFactory
import io.muun.apollo.domain.libwallet.Invoice
import io.muun.apollo.domain.model.Contact
import io.muun.apollo.domain.model.LnUrlWithdraw
import io.muun.apollo.domain.model.Operation
import io.muun.apollo.domain.model.PublicProfile
import io.muun.apollo.domain.model.Sha256Hash
import io.muun.apollo.domain.selector.BitcoinUnitSelector
import io.muun.apollo.domain.utils.locale
import io.muun.apollo.presentation.ui.helper.BitcoinHelper
import io.muun.apollo.presentation.ui.helper.MoneyHelper
import io.muun.apollo.presentation.ui.home.HomeActivity
import io.muun.apollo.presentation.ui.lnurl.withdraw.LnUrlWithdrawActivity
import io.muun.apollo.presentation.ui.operation_detail.OperationDetailActivity
import io.muun.apollo.presentation.ui.utils.OS
import io.muun.apollo.presentation.ui.utils.notificationManager
import io.muun.apollo.presentation.ui.utils.string
import io.muun.common.api.messages.EventCommunicationMessage
import io.muun.common.api.messages.EventCommunicationMessage.Event.TAPROOT_ACTIVATED
import io.muun.common.api.messages.EventCommunicationMessage.Event.TAPROOT_PREACTIVATION
import io.muun.common.model.OperationDirection
import io.muun.common.utils.Preconditions
import rx.Observable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.validation.constraints.NotNull

class NotificationServiceImpl @Inject constructor(
    private val context: Context,
    private val executionTransformerFactory: ExecutionTransformerFactory,
    private val bitcoinUnitSel: BitcoinUnitSelector,
) : NotificationService {

    companion object {
        private const val PROFILE_PICTURE_HEIGHT = 250
        private const val PROFILE_PICTURE_WIDTH = 250
        private const val NOTIF_IMAGE_HEIGHT = 32
        private const val NOTIF_IMAGE_WIDTH = 32
    }

    private val muunChannelName: String
        get() = context.string(R.string.notification_channel_name)

    class MuunNotification(
        val title: String,
        val content: String?,
        val intent: Intent?,
        val onGoing: Boolean = false,
        val actions: MutableList<Action> = mutableListOf(),
        private val id: Int? = null,
    ) {

        fun computeId() =
            id ?: innerComputeId()

        /**
         * Computes a notification id from it's title and content, so every notification is unique.
         */
        private fun innerComputeId(): Int {
            val str = title + content
            return str.hashCode()
        }

        class Action(
            @DrawableRes val icon: Int?,
            val title: CharSequence,
            val intent: PendingIntent? = null, // We'll use the notification default intent
        )

    }

    /**
     * Cancel all previously shown notifications.
     */
    override fun cancelAllNotifications() {
        NotificationManagerCompat
            .from(context)
            .cancelAll()
    }

    /**
     * Cancel a previously shown notification.
     * @param id the ID of the notification
     */
    override fun cancelNotification(id: Int) {
        NotificationManagerCompat
            .from(context)
            .cancel(id)
    }

    /**
     * Cancel a previously shown LNURL withdraw notification.
     * @param paymentHash the paymentHash of invoice associated with the lnurl withdraw
     */
    override fun cancelLnUrlNotification(paymentHash: Sha256Hash) {
        cancelNotification(paymentHash.hashCode())

        WorkManager.getInstance(context).cancelAllWorkByTag(paymentHash.toString())
    }

    /**
     * Show a notification for a new incoming operation.
     */
    override fun showNewOperationNotification(@NotNull operation: Operation) {
        Preconditions.checkNotNull(operation.id)
        Preconditions.checkArgument(operation.direction == OperationDirection.INCOMING)

        if (operation.isExternal) {
            showNewOperationFromNetworkNotification(operation)

        } else {
            Preconditions.checkNotNull(operation.senderProfile)
            showNewOperationFromUserNotification(operation)
        }
    }

    /**
     * Show a notification for a new contact.
     */
    override fun showNewContactNotification(@NotNull contact: Contact) {

        val notification = MuunNotification(
            context.string(R.string.notifications_new_contact, contact.publicProfile.fullName),
            null,
            HomeActivity.getStartActivityIntent(context)
        )

        showNotificationFromContact(notification, contact.publicProfile)
    }

    /**
     * Show a notification for a failed operation.
     */
    override fun showOperationFailedNotification(opId: Long) {

        val notification = MuunNotification(
            context.string(R.string.notifications_op_failed_title),
            context.string(R.string.notifications_op_failed_content),
            OperationDetailActivity.getStartActivityIntent(context, opId)
        )

        showNotification(notification)
    }

    override fun showWaitingForLnPaymentNotification(lnUrlWithdraw: LnUrlWithdraw) {

        val decodedInvoice = Invoice.decodeInvoice(Globals.INSTANCE.network, lnUrlWithdraw.invoice)

        val notification = MuunNotification(
            context.string(R.string.notification_receiving_ln_payment),
            context.string(R.string.notification_receiving_ln_payment_desc, lnUrlWithdraw.service),
            LnUrlWithdrawActivity.getStartActivityIntent(context, lnUrlWithdraw),
            onGoing = true,
            id = decodedInvoice.paymentHash.hashCode()
        )

        showWithDrawable(notification, R.drawable.lightning)
    }

    override fun scheduleLnPaymentExpirationNotification(lnUrlWithdraw: LnUrlWithdraw) {

        val decodedInvoice = Invoice.decodeInvoice(Globals.INSTANCE.network, lnUrlWithdraw.invoice)

        val data = Data.Builder()
            .putString(LnPaymentFailedNotificationWorker.LNURL_WITHDRAW, lnUrlWithdraw.serialize())

        val work = OneTimeWorkRequestBuilder<LnPaymentFailedNotificationWorker>()
            .setInitialDelay(decodedInvoice.remainingMillis(), TimeUnit.MILLISECONDS)
            .setInputData(data.build())
            .addTag(decodedInvoice.paymentHash.hashCode().toString())
            .build()

        WorkManager.getInstance(context).enqueue(work)
    }

    override fun showLnPaymentExpiredNotification(lnUrlWithdraw: LnUrlWithdraw) {

        val decodedInvoice = Invoice.decodeInvoice(Globals.INSTANCE.network, lnUrlWithdraw.invoice)

        cancelLnUrlNotification(decodedInvoice.paymentHash)

        val notification = MuunNotification(
            context.string(R.string.notification_ln_payment_failed),
            context.string(R.string.notification_ln_payment_failed_desc, lnUrlWithdraw.service),
            LnUrlWithdrawActivity.getStartActivityIntent(context, lnUrlWithdraw, true)
        )

        notification.actions.add(MuunNotification.Action(0, context.string(R.string.open_app)))

        showWithDrawable(notification, R.drawable.lightning)
    }

    override fun showIncomingLightningPaymentPending() {
        val notification = MuunNotification(
            context.string(R.string.notification_incoming_ln_payment_pending),
            context.string(R.string.notification_incoming_ln_payment_pending_desc),
            HomeActivity.getStartActivityIntent(context)
        )

        notification.actions.add(
            MuunNotification.Action(
                0, context.string(R.string.notification_incoming_ln_payment_pending_cta)
            )
        )

        showWithDrawable(notification, R.drawable.lightning)
    }

    private fun showNewOperationFromNetworkNotification(op: Operation) {
        val amount = BitcoinHelper.formatShortBitcoinAmount(
            op.amount.inSatoshis,
            bitcoinUnitSel.get(),
            context.locale()
        )

        val contentResId = if (op.isIncomingSwap) {
            R.string.history_external_incoming_swap_description
        } else {
            R.string.history_external_incoming_operation_description
        }

        val notification = MuunNotification(
            context.string(R.string.notifications_amount_received, amount),
            context.string(contentResId),
            OperationDetailActivity.getStartActivityIntent(context, op.id)
        )

        val drawableIcon = if (op.isIncomingSwap) {
            R.drawable.lightning
        } else {
            R.drawable.btc
        }

        showWithDrawable(notification, drawableIcon)
    }

    private fun showNewOperationFromUserNotification(op: Operation) {

        val notification = MuunNotification(
            getNotificationContentMessage(op),
            op.description,
            OperationDetailActivity.getStartActivityIntent(context, op.id)
        )

        showNotificationFromContact(notification, op.senderProfile)
    }

    /**
     * Shows a notification related to a contact.
     *
     *
     * This must be run in the UI thread.
     */
    private fun showNotificationFromContact(noti: MuunNotification, contact: PublicProfile?) {

        if (contact!!.profilePictureUrl == null) {
            showWithDrawable(noti, R.drawable.avatar_badge_grey)
            return
        }

        val requestBuilder = Glide.with(context)
            .asBitmap()
            .load(contact.profilePictureUrl)

        showWithBitmapRequest(noti, requestBuilder)
    }

    override fun showEventCommunication(event: EventCommunicationMessage.Event) {
        val titleRes = when (event) {
            TAPROOT_PREACTIVATION -> R.string.notification_tr_preactivate_title
            TAPROOT_ACTIVATED -> R.string.notification_tr_activated_title
        }

        val descRes = when (event) {
            TAPROOT_PREACTIVATION -> R.string.notification_tr_preactivate_desc
            TAPROOT_ACTIVATED -> R.string.notification_tr_activated_desc
        }

        val notification = MuunNotification(
            context.string(titleRes),
            context.getString(descRes),
            HomeActivity.getStartActivityIntent(context)
        )

        showNotification(notification)
    }

    private fun getNotificationContentMessage(operation: Operation): String {
        val amount = MoneyHelper.formatShortMonetaryAmount(
            operation.amount.inInputCurrency,
            bitcoinUnitSel.get(),
            context.locale()
        )
        val senderName = operation.senderProfile!!.firstName
        return context.string(R.string.notifications_amount_received_from, senderName, amount)
    }

    private fun showWithDrawable(noti: MuunNotification, @DrawableRes drawableId: Int) {

        // This won't handle changes in OS's light/dark theme. Its a limitation of the
        // NotificationCompat API, because it only allows to set a Bitmap as LargeIcon. Since it is
        // the OS who is in charge of re-drawing the notification, it will just re-use the bitmap
        // instead of properly resolving a drawable resource which may have light/dark variants.
        val requestBuilder = Glide.with(context)
            .asBitmap()
            .load(drawableId)  // For some reason, can't use ContextCompat.getDrawable(ctx, resId)

        showWithBitmapRequest(noti, requestBuilder)
    }

    private fun showWithBitmapRequest(noti: MuunNotification, reqBuilder: RequestBuilder<Bitmap>) {

        val updatedBuilder = reqBuilder
            .apply(RequestOptions.overrideOf(PROFILE_PICTURE_WIDTH, PROFILE_PICTURE_HEIGHT))
            .apply(RequestOptions.circleCropTransform())

        val target = object : SimpleTarget<Bitmap>(NOTIF_IMAGE_WIDTH, NOTIF_IMAGE_HEIGHT) {

            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                showNotification(noti, resource)
            }
        }

        Observable.just(updatedBuilder)
            .compose<RequestBuilder<Bitmap?>>(executionTransformerFactory.getAsyncExecutor())
            .subscribe { request: RequestBuilder<Bitmap?> -> request.into(target) }
    }

    private fun showNotification(notification: MuunNotification, bitmap: Bitmap? = null) {

        createMuunNotificationChannel()

        val builder = NotificationCompat.Builder(context, muunChannelName)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(notification.title)
            .setContentText(notification.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.content))
            .setAutoCancel(true)
            .setOngoing(notification.onGoing)
            .setDefaults(Notification.DEFAULT_SOUND)
            .setLights(Color.WHITE, 400, 1500)
            .setVibrate(longArrayOf(0, 800))

        if (notification.onGoing) {
            // Display an indeterminate loading progress bar
            builder.setProgress(0, 0, true)
        }

        if (bitmap != null) {
            builder.setLargeIcon(bitmap)
        }

        val notificationId = notification.computeId()

        if (notification.intent != null) {
            val flags = if (OS.supportsPendingIntentMutabilityFlags()) {
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                notification.intent,
                flags
            )
            builder.setContentIntent(pendingIntent)

            for (action in notification.actions) {
                builder.addAction(action.icon ?: 0, action.title, action.intent ?: pendingIntent)
            }
        }

        if (OS.supportsNotificationRuntimePermission()) {
            val notiPermission = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )

            if (notiPermission != PackageManager.PERMISSION_GRANTED) {
                // We don't have notification runtime permission so don't show notification.
                // Trying to so would throw SecurityException.
                return
            }
        }

        NotificationManagerCompat
            .from(context)
            .notify(notificationId, builder.build())
    }

    /**
     * This is an idempotent operation. Nothing will happen if we try to create
     * a notification channel when an identical one already exists.
     */
    private fun createMuunNotificationChannel() {

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (OS.supportsNotificationChannel()) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channelName = muunChannelName

            // For now we will be only using one channel, so the channelId and channelName will be
            // the same, and the channel name will be the app's name.
            val channel = NotificationChannel(
                channelName, channelName, importance
            )

            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            val notificationManager = context.notificationManager()
            notificationManager.createNotificationChannel(channel)
        }
    }
}