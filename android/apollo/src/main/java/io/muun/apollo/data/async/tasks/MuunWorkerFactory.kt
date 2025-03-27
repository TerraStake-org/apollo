package io.muun.apollo.data.async.tasks

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import io.muun.apollo.data.external.DataComponentProvider
import io.muun.apollo.data.external.NotificationService
import io.muun.apollo.data.os.execution.ExecutionTransformerFactory
import io.muun.apollo.domain.LoggingContextManager
import io.muun.apollo.domain.action.UserActions
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import kotlin.reflect.KClass

class MuunWorkerFactory @Inject constructor(
    private val provider: DataComponentProvider,
    @Inject private val taskDispatcher: TaskDispatcher,
    @Inject private val userActions: UserActions,
    @Inject private val loggingContextManager: LoggingContextManager,
    @Inject private val transformerFactory: ExecutionTransformerFactory,
    @Inject private val notificationService: NotificationService,
    @Inject private val workerProviders: Map<Class<out Worker>, @JvmSuppressWildcards Provider<Worker>>
) : WorkerFactory() {

    init {
        Timber.d("[MuunWorkerFactory] Initializing with dependency injection")
        provider.dataComponent.inject(this)
    }

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return try {
            Timber.d("[MuunWorkerFactory] Creating worker: $workerClassName")
            val workerClass = Class.forName(workerClassName).asSubclass(Worker::class.java)
            
            loggingContextManager.setupCrashlytics()

            when {
                workerProviders.containsKey(workerClass) -> {
                    workerProviders[workerClass]?.get()?.apply {
                        updateParams(workerParameters)
                    }
                }
                else -> {
                    Timber.w("[MuunWorkerFactory] No provider found for $workerClassName, falling back")
                    null
                }
            }
        } catch (e: ClassNotFoundException) {
            Timber.e(e, "[MuunWorkerFactory] Failed to find worker class: $workerClassName")
            null
        } catch (e: Exception) {
            Timber.e(e, "[MuunWorkerFactory] Error creating worker: $workerClassName")
            null
        }
    }

    companion object {
        /**
         * Extension function to update worker parameters for workers created via DI
         */
        private fun Worker.updateParams(params: WorkerParameters): Worker {
            return this.apply {
                // Reflection approach to update final fields if needed
                // Alternative: Have workers implement a ParamUpdateable interface
                try {
                    val field = Worker::class.java.getDeclaredField("mAppContext").apply {
                        isAccessible = true
                    }
                    field.set(this, params)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to update worker parameters")
                }
            }
        }
    }
}
