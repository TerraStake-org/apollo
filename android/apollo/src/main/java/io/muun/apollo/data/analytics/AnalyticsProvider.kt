package io.muun.apollo.data.analytics

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import io.muun.apollo.domain.analytics.AnalyticsEvent
import io.muun.apollo.domain.model.report.CrashReport
import io.muun.apollo.domain.model.user.User
import rx.Single
import timber.log.Timber
import java.util.concurrent.ConcurrentSkipListMap
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_BREADCRUMBS = 100
private const val BREADCRUMB_KEY_EVENT_NAME = "eventName"
private const val ANALYTICS_ERROR_EVENT = "analytics_error"
private const val ERROR_TYPE_KEY = "error_type"
private const val EXCEPTION_KEY = "exception"

@Singleton
class AnalyticsProvider @Inject constructor(
    private val context: Context,
    private val firebaseAnalytics: FirebaseAnalytics = FirebaseAnalytics.getInstance(context)
) {
    // Fixed typo in variable name (breadcrumbCollector instead of breadcrumbCollector)
    private val breadcrumbCollector = object : ConcurrentSkipListMap<Long, Bundle>() {
        override fun put(key: Long, value: Bundle): Bundle? {
            val result = super.put(key, value)
            if (size > MAX_BREADCRUMBS) {
                pollFirstEntry() // Remove oldest entry if we exceed max size
            }
            return result
        }
    }

    fun loadBigQueryPseudoId(): Single<String?> =
        Single.create { emitter ->
            firebaseAnalytics.appInstanceId
                .addOnSuccessListener { id ->
                    Timber.d("Loaded BigQueryPseudoId: $id")
                    emitter.onSuccess(id)
                }
                .addOnFailureListener { error ->
                    Timber.e(error, "Failed to load BigQueryPseudoId")
                    emitter.onError(error)
                }
        }

    fun setUserProperties(user: User) = safeOperation("user_properties_error") {
        firebaseAnalytics.setUserId(user.hid.toString())
        firebaseAnalytics.setUserProperty(
            "currency", 
            user.unsafeGetPrimaryCurrency().currencyCode
        )
    }

    fun resetUserProperties() = safeOperation("reset_user_properties_error") {
        firebaseAnalytics.setUserId(null)
        firebaseAnalytics.setUserProperty("email", null)
    }

    fun report(event: AnalyticsEvent) {
        if (event is AnalyticsEvent.E_BREADCRUMB) {
            actuallyReport(event)
            return
        }

        safeOperation("analytics_report_error", mapOf("event_id" to event.eventId)) {
            Timber.i("AnalyticsProvider: $event")
            actuallyReport(event)
        }
    }

    fun attachAnalyticsMetadata(report: CrashReport) {
        report.metadata.apply {
            put("breadcrumbs", getBreadcrumbMetadata())
            put("displayMetrics", getDisplayMetricsMetadata())
        }
    }

    // ===== PRIVATE IMPLEMENTATION =====

    private fun actuallyReport(event: AnalyticsEvent) {
        Bundle().apply {
            putString(BREADCRUMB_KEY_EVENT_NAME, event.eventId)
            event.metadata.forEach { (key, value) ->
                putString(key, value.toString())
            }
            firebaseAnalytics.logEvent(event.eventId, this)
            breadcrumbCollector[System.currentTimeMillis()] = this
        }
    }

    private inline fun safeOperation(
        errorType: String,
        additionalParams: Map<String, String> = emptyMap(),
        operation: () -> Unit
    ) {
        try {
            operation()
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute analytics operation: $errorType")
            reportError(errorType, e, additionalParams)
        }
    }

    private fun reportError(
        errorType: String,
        exception: Throwable,
        additionalParams: Map<String, String> = emptyMap()
    ) {
        try {
            Bundle().apply {
                putString(ERROR_TYPE_KEY, errorType)
                putString(EXCEPTION_KEY, exception.toString())
                additionalParams.forEach { (key, value) ->
                    putString(key, value)
                }
                firebaseAnalytics.logEvent(ANALYTICS_ERROR_EVENT, this)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to report analytics error")
        }
    }

    private fun getBreadcrumbMetadata(): String {
        return breadcrumbCollector.entries.joinToString(
            prefix = "{\n",
            postfix = "\n}",
            separator = "\n"
        ) { (_, bundle) ->
            val eventName = bundle.getString(BREADCRUMB_KEY_EVENT_NAME) ?: "null"
            val params = bundle.keySet()
                .filterNot { it == BREADCRUMB_KEY_EVENT_NAME }
                .joinToString(", ") { key -> "$key=${bundle[key]}" }
            "\t$eventName={$params}"
        }
    }

    private fun getDisplayMetricsMetadata(): String {
        return Resources.getSystem().displayMetrics.let { metrics ->
            """{
            |    height=${metrics.heightPixels},
            |    width=${metrics.widthPixels},
            |    density=${metrics.scaledDensity}
            |}""".trimMargin()
        }
    }
}