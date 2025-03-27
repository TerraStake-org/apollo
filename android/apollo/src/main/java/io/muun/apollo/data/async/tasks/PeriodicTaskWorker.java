package io.muun.apollo.data.async.tasks

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.muun.apollo.data.os.execution.ExecutionTransformerFactory
import io.muun.apollo.domain.action.UserActions
import io.muun.apollo.domain.errors.PeriodicTaskError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

class PeriodicTaskWorker @Inject constructor(
    context: Context,
    params: WorkerParameters,
    private val taskDispatcher: TaskDispatcher,
    private val userActions: UserActions,
    private val transformerFactory: ExecutionTransformerFactory
) : CoroutineWorker(context, params) {

    companion object {
        const val TASK_TYPE_KEY = "taskType"
        private const val TAG = "PeriodicTaskWorker"
    }

    init {
        Timber.tag(TAG).d("Initializing PeriodicTaskWorker")
    }

    override suspend fun doWork(): Result {
        if (!userActions.isLoggedIn()) {
            Timber.tag(TAG).d("User not logged in, skipping task")
            return Result.success()
        }

        val type = requireNotNull(inputData.getString(TASK_TYPE_KEY)) {
            "Task type must be provided in input data"
        }

        Timber.tag(TAG).d("Running periodic task of type: $type")
        val startTime = SystemClock.elapsedRealtime()

        return try {
            executeTask(type, startTime)
            Result.success()
        } catch (e: Exception) {
            handleTaskError(type, startTime, e)
        }
    }

    private suspend fun executeTask(type: String, startTime: Long) {
        withContext(Dispatchers.IO) {
            taskDispatcher.dispatch(type)
                .compose(transformerFactory.getAsyncExecutor())
                .doOnError { error ->
                    logTaskError(type, error)
                }
                .await()
        }

        val duration = (SystemClock.elapsedRealtime() - startTime).milliseconds
        Timber.tag(TAG).d("Successfully completed task $type in $duration")
    }

    private fun logTaskError(type: String, error: Throwable) {
        val enhancedError = if (error.stackTrace.isNullOrEmpty()) {
            PeriodicTaskError(
                type = type,
                cause = RuntimeException(
                    "Exception with no stacktrace while running periodic task of type $type",
                    error
                )
            )
        } else {
            PeriodicTaskError(type, error)
        }

        Timber.tag(TAG).e(enhancedError)
    }

    private fun handleTaskError(type: String, startTime: Long, error: Exception): Result {
        val duration = (SystemClock.elapsedRealtime() - startTime).milliseconds
        Timber.tag(TAG).e(error, "Failed to complete task $type after $duration")

        return when {
            // Retry for transient errors
            error.isTransient() -> Result.retry()
            // Success for known non-retryable errors
            error.isNonRetryable() -> Result.success()
            // Failure for other cases
            else -> Result.failure()
        }
    }

    override fun onStopped() {
        Timber.tag(TAG).d("PeriodicTaskWorker stopped")
        super.onStopped()
    }
}

// Extension functions for error classification
private fun Throwable.isTransient(): Boolean {
    return this is java.net.UnknownHostException || 
           this is java.net.ConnectException
}

private fun Throwable.isNonRetryable(): Boolean {
    return this is IllegalArgumentException ||
           this is IllegalStateException
}
