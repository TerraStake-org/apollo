package io.muun.apollo.data.async.tasks

import io.muun.apollo.domain.action.ContactActions
import io.muun.apollo.domain.action.NotificationActions
import io.muun.apollo.domain.action.address.SyncExternalAddressIndexesAction
import io.muun.apollo.domain.action.incoming_swap.RegisterInvoicesAction
import io.muun.apollo.domain.action.integrity.IntegrityAction
import io.muun.apollo.domain.action.realtime.FetchRealTimeDataAction
import io.muun.apollo.domain.errors.PeriodicTaskOnMainThreadError
import io.muun.apollo.domain.errors.TaskDispatchError
import io.reactivex.rxjava3.core.Completable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskDispatcher @Inject constructor(
    private val contactActions: ContactActions,
    private val notificationActions: NotificationActions,
    private val integrityAction: IntegrityAction,
    private val fetchRealTimeData: FetchRealTimeDataAction,
    private val syncExternalAddressIndexes: SyncExternalAddressIndexesAction,
    private val registerInvoices: RegisterInvoicesAction
) {
    private val taskHandlers = mutableMapOf<String, () -> Completable>()

    init {
        Timber.d("[TaskDispatcher] Initializing task handlers")
        registerTasks()
    }

    private fun registerTasks() {
        registerTask("pullNotifications") { notificationActions.pullNotifications().ignoreElements() }
        registerTask("syncRealTimeData") { fetchRealTimeData.action().ignoreElements() }
        registerTask("syncPhoneContacts") { contactActions.syncPhoneContacts().ignoreElements() }
        registerTask("syncExternalAddressesIndexes") { 
            syncExternalAddressIndexes.action().ignoreElements() 
        }
        registerTask("registerInvoices") { registerInvoices.action().ignoreElements() }
        registerTask("checkIntegrity") { integrityAction.checkIntegrity().ignoreElements() }
    }

    /**
     * Dispatch a task to the corresponding handler
     * @param taskName Name of the task to execute
     * @return Completable that represents the task execution
     * @throws TaskDispatchError if the task name is invalid or execution fails
     */
    fun dispatch(taskName: String): Completable {
        return Completable.fromAction {
            if (isOnMainThread()) {
                val error = PeriodicTaskOnMainThreadError(taskName)
                Timber.e(error)
                throw error
            }
        }.andThen(
            taskHandlers[taskName]?.invoke() 
                ?: Completable.error(TaskDispatchError("Unrecognized task type: $taskName"))
        ).doOnSubscribe { 
            Timber.d("Dispatching task: $taskName") 
        }.doOnComplete { 
            Timber.d("Task completed successfully: $taskName") 
        }.doOnError { error -> 
            Timber.e(error, "Task failed: $taskName") 
        }
    }

    /**
     * Register a new task type with its handler
     * @param type The unique identifier for the task type
     * @param handler The function that executes the task
     */
    private fun registerTask(type: String, handler: () -> Completable) {
        taskHandlers[type] = handler
        Timber.v("Registered task: $type")
    }

    private fun isOnMainThread(): Boolean {
        return Looper.getMainLooper() == Looper.myLooper()
    }

    companion object {
        const val PULL_NOTIFICATIONS = "pullNotifications"
        const val SYNC_REAL_TIME_DATA = "syncRealTimeData"
        const val SYNC_PHONE_CONTACTS = "syncPhoneContacts"
        const val SYNC_EXTERNAL_ADDRESSES = "syncExternalAddressesIndexes"
        const val REGISTER_INVOICES = "registerInvoices"
        const val CHECK_INTEGRITY = "checkIntegrity"
    }
}
