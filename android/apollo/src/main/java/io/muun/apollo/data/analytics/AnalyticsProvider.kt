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

@Singleton
class AnalyticsProvider @Inject constructor(
    private val context: Context,
    private val firebaseAnalytics: FirebaseAnalytics = FirebaseAnalytics.getInstance(context)
) {
    // Thread-safe sorted map for breadcrumbs with size limit
    private val breadcrumbCollector = ConcurrentSkipListMap<Long, Bundle>().apply {
        // Ensure we don't keep unlimited breadcrumbs
        object : LinkedHashMap<Long, Bundle>(MAX_BREADCRUMBS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bundle>): Boolean {
                return size > MAX_BREADCRUMBS
            }
        }
    }

    /**
     * Get the Firebase Analytics app instance ID (BigQuery pseudo ID)
     */
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

    /**
     * Set the user's properties for analytics tracking
     */
    fun setUserProperties(user: User) {
        try {
            firebaseAnalytics.setUserId(user.hid.toString())
            firebaseAnalytics.setUserProperty(
                "currency", 
                user.unsafeGetPrimaryCurrency().currencyCode
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to set user properties")
            reportError("user_properties_error", e)
        }
    }

    /**
     * Reset all user properties (on logout)
     */
    fun resetUserProperties() {
        try {
            firebaseAnalytics.setUserId(null)
            firebaseAnalytics.setUserProperty("email", null)
        } catch (e: Exception) {
            Timber.e(e, "Failed to reset user properties")
            reportError("reset_user_properties_error", e)
        }
    }

    /**
     * Report an analytics event
     */
    fun report(event: AnalyticsEvent) {
        if (event is AnalyticsEvent.E_BREADCRUMB) {
            // Skip logging breadcrumbs to avoid recursion
            actuallyReport(event)
            return
        }

        try {
            Timber.i("AnalyticsProvider: $event")
            actuallyReport(event)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to report analytics event: ${event.eventId}")
            reportError("analytics_report_error", e, mapOf(
                "event_id" to event.eventId
            ))
        }
    }

    /**
     * Attach analytics metadata to crash reports
     */
    fun attachAnalyticsMetadata(report: CrashReport) {
        report.metadata.apply {
            put("breadcrumbs", getBreadcrumbMetadata())
            put("displayMetrics", getDisplayMetricsMetadata())
        }
    }

    // ===== PRIVATE IMPLEMENTATION =====

    private fun actuallyReport(event: AnalyticsEvent) {
        val bundle = Bundle().apply {
            putString(BREADCRUMB_KEY_EVENT_NAME, event.eventId)
            event.metadata.forEach { (key, value) ->
                putString(key, value.toString())
            }
        }

        firebaseAnalytics.logEvent(event.eventId, bundle)
        breadcrumbCollector[System.currentTimeMillis()] = bundle
    }

    private fun reportError(
        errorType: String,
        exception: Throwable,
        additionalParams: Map<String, String> = emptyMap()
    ) {
        try {
            val bundle = Bundle().apply {
                putString("error_type", errorType)
                putString("exception", exception.toString())
                additionalParams.forEach { (key, value) ->
                    putString(key, value)
                }
            }
            firebaseAnalytics.logEvent("analytics_error", bundle)
        } catch (e: Exception) {
            Timber.e(e, "Failed to report analytics error")
        }
    }

    private fun getBreadcrumbMetadata(): String {
        return buildString {
            append("{\n")
            breadcrumbCollector.forEach { (timestamp, bundle) ->
                append("\t${bundle.getString(BREADCRUMB_KEY_EVENT_NAME)}={")
                append(serializeBundle(bundle))
                append("}\n")
            }
            append("}")
        }
    }

    private fun getDisplayMetricsMetadata(): String {
        val displayMetrics = Resources.getSystem().displayMetrics
        val bundle = Bundle().apply {
            putInt("height", displayMetrics.heightPixels)
            putInt("width", displayMetrics.widthPixels)
            putFloat("density", displayMetrics.scaledDensity)
        }
        return "{\n\t${serializeBundle(bundle)}\n}"
    }

    private fun serializeBundle(bundle: Bundle): String {
        return bundle.keySet()
            .filterNot { it == BREADCRUMB_KEY_EVENT_NAME }
            .joinToString(", ") { key ->
                "$key=${bundle[key]}"
            }
    }
}
