// Android Framework
import android.content.Context
import android.content.Intent
import android.net.Uri

// Google Auth & Drive
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as FileMetadata
import com.google.api.services.drive.model.Revision

// Kotlin Coroutines
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

// Dependency Injection
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

// RxJava (if maintaining backward compatibility)
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers

// Utilities
import io.muun.common.utils.Preconditions
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor
import kotlin.contracts.contract
@Singleton
class DriveManager @Inject constructor(
    private val context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val signInClient: GoogleSignInClient,
    private val driveService: Drive
) : DriveAuthenticator, DriveUploader {

    override fun getSignInIntent(): Intent = signInClient.signInIntent

    override suspend fun getSignedInAccount(resultIntent: Intent?): GoogleSignInAccount =
        withContext(dispatcher) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(resultIntent)
                task.await() ?: throw DriveError(NullPointerException("Null account returned"))
            } catch (e: Exception) {
                Timber.e(e, "Failed to get signed in account")
                throw DriveError(e)
            }
        }

    override fun upload(
        file: File,
        mimeType: String,
        uniqueProp: String,
        props: Map<String, String>
    ): Flow<DriveFile> = flow {
        require(props.containsKey(uniqueProp)) { 
            "Missing required property: $uniqueProp" 
        }
        require(file.exists()) { "File ${file.path} doesn't exist" }

        val driveFile = withContext(dispatcher) {
            executeUpload(file, mimeType, uniqueProp, props)
        }
        emit(driveFile)
    }.catch { error ->
        Timber.e(error, "Failed to upload file")
        throw DriveError(error)
    }

    override suspend fun signOut() {
        try {
            signInClient.revokeAccess().await()
            signInClient.signOut().await()
            Timber.d("Successfully signed out from Drive")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sign out from Drive")
            throw DriveError(e)
        }
    }

    private suspend fun executeUpload(
        file: File,
        mimeType: String,
        uniqueProp: String,
        props: Map<String, String>
    ): DriveFile {
        val folder = getOrCreateMuunFolder()
        val updateCandidates = getUpdateCandidates(file.name, mimeType, folder)
        val fileToUpdate = findFileToUpdate(updateCandidates, uniqueProp, props[uniqueProp]!!)

        return if (fileToUpdate != null) {
            updateFile(fileToUpdate, file, props, keepRevision = true)
        } else {
            createFile(mimeType, file, folder, props)
        }
    }

    private suspend fun getOrCreateMuunFolder(): DriveFile =
        getExistingMuunFolder() ?: createNewMuunFolder()

    private suspend fun createFile(
        mimeType: String,
        file: File,
        folder: DriveFile,
        props: Map<String, String>
    ): DriveFile {
        val metadata = FileMetadata().apply {
            parents = listOf(folder.id)
            this.mimeType = mimeType
            name = file.name
            appProperties = props
        }

        return driveService.files()
            .create(metadata, FileContent(mimeType, file))
            .setFields(ALL_FIELDS)
            .await()
            .toDriveFile(folder)
    }

    companion object {
        private const val MAX_RESULTS = 100
        private const val ALL_FIELDS = "*"
        private const val DRIVE_FOLDER_TYPE = "application/vnd.google-apps.folder"
        private const val DRIVE_FOLDER_NAME = "Muun"
        private const val DRIVE_FOLDER_PARENT = "root"
    }
}

// Factory for DI
@Module
@InstallIn(SingletonComponent::class)
object DriveModule {

    @Provides
    @Singleton
    fun provideGoogleSignInClient(
        context: Context,
        @DriveScopes scopes: List<Scope>
    ): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder()
            .apply { scopes.forEach { requestScopes(it) } }
            .requestEmail()
            .build()

        return GoogleSignIn.getClient(context, options)
    }

    @Provides
    @Singleton
    fun provideDriveService(
        context: Context,
        credential: GoogleAccountCredential
    ): Drive = Drive.Builder(
        NetHttpTransport(),
        GsonFactory(),
        credential
    ).setApplicationName("Muun")
     .build()

    @Provides
    @Singleton
    fun provideGoogleAccountCredential(
        context: Context,
        @DriveScopes scopes: List<String>
    ): GoogleAccountCredential = GoogleAccountCredential
        .usingOAuth2(context, scopes)

    @Provides
    @DriveScopes
    fun provideDriveScopes(): List<String> = listOf(DriveScopes.DRIVE_FILE)

    @Provides
    @DriveScopes
    fun provideGoogleScopes(): List<Scope> = listOf(Scope(Scopes.DRIVE_FILE))
}

// Extension functions
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        when {
            task.isCanceled -> cont.cancel()
            task.isSuccessful -> cont.resume(task.result)
            else -> cont.resumeWithException(task.exception ?: Exception("Unknown error"))
        }
    }
}

private fun FileMetadata.toDriveFile(parent: DriveFile? = null): DriveFile = DriveFile(
    id = checkNotNull(id) { "File ID is required" },
    revisionId = headRevisionId ?: "",
    name = checkNotNull(name) { "File name is required" },
    mimeType = checkNotNull(mimeType) { "MIME type is required" },
    size = size ?: 0L,
    link = webViewLink ?: "",
    parent = parent,
    properties = appProperties ?: emptyMap()
)