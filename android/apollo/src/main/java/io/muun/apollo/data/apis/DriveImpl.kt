// Core imports remain the same, adding new ones as needed
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as FileMetadata
import com.google.api.services.drive.model.Revision
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.muun.common.utils.Preconditions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

// Custom qualifiers
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DriveScopes

// Data models
data class DriveFile(
    val id: String,
    val revisionId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val link: String,
    val parent: DriveFile? = null,
    val properties: Map<String, String> = emptyMap()
) {
    fun isGoogleDoc(): Boolean = mimeType.startsWith("application/vnd.google-apps.")
}

data class UploadProgress(
    val bytesUploaded: Long,
    val totalBytes: Long,
    val status: UploadStatus
)

enum class UploadStatus {
    STARTED, IN_PROGRESS, COMPLETED, FAILED
}

// Custom exceptions
sealed class DriveException(message: String) : Exception(message) {
    class AuthenticationFailed(cause: Throwable) : DriveException("Authentication failed: ${cause.message}")
    class UploadFailed(cause: Throwable) : DriveException("Upload failed: ${cause.message}")
    class NetworkError(cause: Throwable) : DriveException("Network error: ${cause.message}")
    class FileNotFound(message: String) : DriveException(message)
}

// Interfaces
interface DriveAuthenticator {
    fun getSignInIntent(): Intent
    suspend fun getSignedInAccount(resultIntent: Intent?): GoogleSignInAccount
    suspend fun signOut()
}

interface DriveUploader {
    fun upload(
        file: File,
        mimeType: String,
        uniqueProp: String,
        props: Map<String, String>
    ): Flow<UploadProgress>
    
    suspend fun openFile(activityContext: Context, driveFile: DriveFile)
}

// Network checking
interface NetworkChecker {
    fun isConnected(): Boolean
}

class NetworkCheckerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkChecker {
    override fun isConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) 
            as ConnectivityManager
        return connectivityManager.activeNetwork != null
    }
}

// Main implementation
@Singleton
class DriveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val signInClient: GoogleSignInClient,
    private val driveService: Drive,
    private val networkChecker: NetworkChecker
) : DriveAuthenticator, DriveUploader {

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val ALL_FIELDS = "id,name,mimeType,size,webViewLink,appProperties,headRevisionId,parents"
        private const val DRIVE_FOLDER_TYPE = "application/vnd.google-apps.folder"
        private const val DRIVE_FOLDER_NAME = "Muun"
    }

    override fun getSignInIntent(): Intent = signInClient.signInIntent

    override suspend fun getSignedInAccount(resultIntent: Intent?): GoogleSignInAccount =
        withContext(dispatcher) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(resultIntent)
                task.await() ?: throw DriveException.AuthenticationFailed(NullPointerException("Null account"))
            } catch (e: ApiException) {
                throw DriveException.AuthenticationFailed(e)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get signed in account")
                throw DriveException.NetworkError(e)
            }
        }

    override fun upload(
        file: File,
        mimeType: String,
        uniqueProp: String,
        props: Map<String, String>
    ): Flow<UploadProgress> = flow {
        validateUploadParams(file, uniqueProp, props)
        
        emit(UploadProgress(0, file.length(), UploadStatus.STARTED))
        val folder = getOrCreateMuunFolder()
        val updateCandidates = getUpdateCandidates(file.name, mimeType, folder)
        val fileToUpdate = findFileToUpdate(updateCandidates, uniqueProp, props[uniqueProp]!!)

        val driveFile = withContext(dispatcher) {
            if (fileToUpdate != null) {
                updateFile(fileToUpdate, file, props, keepRevision = true) { bytes ->
                    emit(UploadProgress(bytes, file.length(), UploadStatus.IN_PROGRESS))
                }
            } else {
                createFile(mimeType, file, folder, props) { bytes ->
                    emit(UploadProgress(bytes, file.length(), UploadStatus.IN_PROGRESS))
                }
            }
        }
        emit(UploadProgress(file.length(), file.length(), UploadStatus.COMPLETED))
    }.flowOn(dispatcher)
     .retryWhen { cause, attempt ->
         if (attempt < MAX_RETRIES && cause is DriveException.NetworkError) {
             delay(RETRY_DELAY_MS)
             true
         } else {
             false
         }
     }.catch { error ->
         Timber.e(error, "Upload failed after retries")
         emit(UploadProgress(0, 0, UploadStatus.FAILED))
         throw DriveException.UploadFailed(error)
     }

    override suspend fun openFile(activityContext: Context, driveFile: DriveFile) {
        try {
            val link = driveFile.parent?.link ?: driveFile.link
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open Drive file")
            throw DriveException.FileNotFound("Failed to open file: ${e.message}")
        }
    }

    override suspend fun signOut() = withContext(dispatcher) {
        try {
            signInClient.revokeAccess().await()
            signInClient.signOut().await()
            Timber.d("Successfully signed out")
        } catch (e: Exception) {
            Timber.e(e, "Sign out failed")
            throw DriveException.AuthenticationFailed(e)
        }
    }

    private fun validateUploadParams(file: File, uniqueProp: String, props: Map<String, String>) {
        Preconditions.checkArgument(props.containsKey(uniqueProp), "Missing required property: $uniqueProp")
        Preconditions.checkArgument(file.exists(), "File ${file.path} doesn't exist")
        Preconditions.checkArgument(networkChecker.isConnected(), "No network connection")
    }

    private suspend fun executeUploadWithProgress(
        metadata: FileMetadata,
        file: File,
        mimeType: String,
        progressCallback: (Long) -> Unit
    ): FileMetadata {
        val content = FileContent(mimeType, file)
        return driveService.files()
            .create(metadata, content)
            .setFields(ALL_FIELDS)
            .setUploadProgressListener { bytesSent ->
                progressCallback(bytesSent)
            }
            .await()
    }

    private suspend fun createFile(
        mimeType: String,
        file: File,
        folder: DriveFile,
        props: Map<String, String>,
        progressCallback: (Long) -> Unit
    ): DriveFile {
        val metadata = FileMetadata().apply {
            parents = listOf(folder.id)
            this.mimeType = mimeType
            name = file.name
            appProperties = props
        }
        return executeUploadWithProgress(metadata, file, mimeType, progressCallback)
            .toDriveFile(folder)
    }

    private suspend fun updateFile(
        fileToUpdate: DriveFile,
        file: File,
        props: Map<String, String>,
        keepRevision: Boolean,
        progressCallback: (Long) -> Unit
    ): DriveFile {
        val metadata = FileMetadata().apply {
            appProperties = props
        }
        return driveService.files()
            .update(fileToUpdate.id, metadata, FileContent(fileToUpdate.mimeType, file))
            .setFields(ALL_FIELDS)
            .setUploadProgressListener { progressCallback(it) }
            .apply { if (keepRevision) set("keepRevisionForever", true) }
            .await()
            .toDriveFile(fileToUpdate.parent)
    }

    private suspend fun getOrCreateMuunFolder(): DriveFile = withContext(dispatcher) {
        getExistingMuunFolder() ?: createNewMuunFolder()
    }

    private suspend fun getExistingMuunFolder(): DriveFile? {
        val query = """
            mimeType='$DRIVE_FOLDER_TYPE' and 
            name='$DRIVE_FOLDER_NAME' and 
            trashed=false
        """.trimIndent()

        return driveService.files().list()
            .setQ(query)
            .setFields(ALL_FIELDS)
            .execute()
            .files
            .firstOrNull()
            ?.toDriveFile()
    }

    private suspend fun createNewMuunFolder(): DriveFile {
        val metadata = FileMetadata().apply {
            mimeType = DRIVE_FOLDER_TYPE
            name = DRIVE_FOLDER_NAME
        }
        return driveService.files()
            .create(metadata)
            .setFields(ALL_FIELDS)
            .await()
            .toDriveFile()
    }

    private suspend fun getUpdateCandidates(
        name: String,
        mimeType: String,
        folder: DriveFile
    ): List<DriveFile> {
        val query = """
            mimeType='$mimeType' and 
            name='$name' and 
            '${folder.id}' in parents and
            trashed=false
        """.trimIndent()

        return driveService.files().list()
            .setQ(query)
            .setFields(ALL_FIELDS)
            .execute()
            .files
            .map { it.toDriveFile(folder) }
    }

    private fun findFileToUpdate(
        candidates: List<DriveFile>,
        uniqueProp: String,
        uniqueValue: String
    ): DriveFile? {
        return when {
            candidates.size == 1 && !candidates[0].properties.containsKey(uniqueProp) -> 
                candidates[0]
            else -> 
                candidates.find { it.properties[uniqueProp] == uniqueValue }
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
}

// DI Module
@Module
@InstallIn(SingletonComponent::class)
object DriveModule {

    @Provides
    @Singleton
    fun provideGoogleSignInClient(
        @ApplicationContext context: Context,
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
        @ApplicationContext context: Context,
        @DriveScopes scopes: List<String>
    ): GoogleAccountCredential = GoogleAccountCredential
        .usingOAuth2(context, scopes)
        .apply {
            backOff = ExponentialBackOff()
        }

    @Provides
    @DriveScopes
    fun provideDriveScopes(): List<String> = listOf(DriveScopes.DRIVE_FILE)

    @Provides
    @DriveScopes
    fun provideGoogleScopes(): List<Scope> = listOf(Scope(Scopes.DRIVE_FILE))

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}