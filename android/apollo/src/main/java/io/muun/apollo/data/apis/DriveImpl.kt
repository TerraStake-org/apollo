package io.muun.apollo.data.apis

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.Revision
import io.muun.common.utils.Preconditions
import rx.Observable
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_RESULTS = 100
private const val ALL_FIELDS = "*"
private const val DRIVE_FOLDER_TYPE = "application/vnd.google-apps.folder"
private const val DRIVE_FOLDER_NAME = "Muun"
private const val DRIVE_FOLDER_PARENT = "root"

@Singleton
class DriveImpl @Inject constructor(
    private val context: Context,
    private val executor: Executor
) : DriveAuthenticator, DriveUploader {

    private val signInClient by lazy { createSignInClient() }

    override fun getSignInIntent(): Intent = signInClient.signInIntent

    override fun getSignedInAccount(resultIntent: Intent?): GoogleSignInAccount {
        return try {
            val completedTask = GoogleSignIn.getSignedInAccountFromIntent(resultIntent)
            if (completedTask.isSuccessful) {
                completedTask.result ?: throw DriveError("Null account returned")
            } else {
                throw DriveError(completedTask.exception ?: Exception("Unknown error"))
            }
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
    ): Observable<DriveFile> {
        Preconditions.checkArgument(props.containsKey(uniqueProp))
        
        val resultSubject = PublishSubject.create<DriveFile>()
        
        Tasks.call(executor) {
            executeUpload(file, mimeType, uniqueProp, props)
        }.addOnSuccessListener { driveFile ->
            resultSubject.onNext(driveFile)
            resultSubject.onCompleted()
        }.addOnFailureListener { error ->
            Timber.e(error, "Failed to upload file")
            resultSubject.onError(DriveError(error))
            resultSubject.onCompleted()
        }

        return resultSubject
            .asObservable()
            .observeOn(Schedulers.from(executor))
    }

    override fun open(activityContext: Context, driveFile: DriveFile) {
        try {
            val link = driveFile.parent?.link ?: driveFile.link
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open Drive file")
            throw DriveError(e)
        }
    }

    override fun signOut() {
        signInClient.revokeAccess()
            .continueWith { signInClient.signOut() }
            .addOnCompleteListener {
                Timber.d("Successfully signed out from Drive")
            }
            .addOnFailureListener { error ->
                Timber.e(error, "Failed to sign out from Drive")
            }
    }

    private fun createDriveService(): Drive {
        return try {
            val credential = GoogleAccountCredential
                .usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))
                .apply {
                    selectedAccount = GoogleSignIn.getLastSignedInAccount(context)?.account
                        ?: throw IllegalStateException("No signed in account")
                }

            Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
                .setApplicationName("Muun")
                .build()
        } catch (e: Exception) {
            Timber.e(e, "Failed to create Drive service")
            throw DriveError(e)
        }
    }

    private fun createSignInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder()
            .requestScopes(Scope(Scopes.DRIVE_FILE))
            .requestEmail()
            .build()

        return GoogleSignIn.getClient(context, options)
    }

    private fun executeUpload(
        file: File,
        mimeType: String,
        uniqueProp: String,
        props: Map<String, String>
    ): DriveFile {
        val driveService = createDriveService()
        val folder = getOrCreateMuunFolder(driveService)

        val updateCandidates = getUpdateCandidates(driveService, file.name, mimeType, folder)
        val fileToUpdate = findFileToUpdate(updateCandidates, uniqueProp, props[uniqueProp]!!)

        return if (fileToUpdate != null) {
            updateFile(driveService, fileToUpdate, file, props, keepRevision = true)
        } else {
            createFile(driveService, mimeType, file, folder, props)
        }
    }

    private fun getOrCreateMuunFolder(driveService: Drive): DriveFile {
        return getExistingMuunFolder(driveService) ?: createNewMuunFolder(driveService)
    }

    private fun createFile(
        driveService: Drive,
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

        val content = FileContent(mimeType, file)

        return driveService.files()
            .create(metadata, content)
            .setFields(ALL_FIELDS)
            .execute()
            .let { toDriveFile(it, folder) }
    }

    private fun updateFile(
        driveService: Drive,
        existingFile: DriveFile,
        newContent: File,
        newProps: Map<String, String>,
        keepRevision: Boolean
    ): DriveFile {
        if (keepRevision) {
            setKeepRevision(driveService, existingFile)
        }

        val metadata = FileMetadata().apply {
            appProperties = newProps
        }

        val content = FileContent(existingFile.mimeType, newContent)

        return driveService.files()
            .update(existingFile.id, metadata, content)
            .setFields(ALL_FIELDS)
            .execute()
            .let { toDriveFile(it, existingFile.parent) }
    }

    private fun setKeepRevision(driveService: Drive, file: DriveFile) {
        try {
            val revision = Revision().apply {
                keepForever = true
            }
            driveService.revisions()
                .update(file.id, file.revisionId, revision)
                .execute()
        } catch (e: Exception) {
            Timber.e(e, "Failed to set keepForever on revision")
        }
    }

    private fun createNewMuunFolder(driveService: Drive): DriveFile {
        val metadata = FileMetadata().apply {
            parents = listOf(DRIVE_FOLDER_PARENT)
            mimeType = DRIVE_FOLDER_TYPE
            name = DRIVE_FOLDER_NAME
        }

        return driveService.files()
            .create(metadata)
            .setFields(ALL_FIELDS)
            .execute()
            .let { toDriveFile(it) }
    }

    private fun getExistingMuunFolder(driveService: Drive): DriveFile? {
        val query = buildDriveQuery(
            mimeType = DRIVE_FOLDER_TYPE,
            name = DRIVE_FOLDER_NAME,
            parentId = DRIVE_FOLDER_PARENT
        )

        return driveService.files()
            .list()
            .setQ(query)
            .setFields(ALL_FIELDS)
            .execute()
            .files
            .firstOrNull()
            ?.let { toDriveFile(it) }
    }

    private fun getUpdateCandidates(
        driveService: Drive,
        name: String,
        mimeType: String,
        folder: DriveFile
    ): List<DriveFile> {
        val query = buildDriveQuery(
            mimeType = mimeType,
            name = name,
            parentId = folder.id
        )

        return driveService.files()
            .list()
            .setQ(query)
            .setFields(ALL_FIELDS)
            .setMaxResults(MAX_RESULTS.toLong())
            .execute()
            .files
            .map { toDriveFile(it) }
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

    private fun buildDriveQuery(
        mimeType: String,
        name: String,
        parentId: String
    ): String {
        return """
            mimeType='$mimeType' and 
            name='$name' and 
            '$parentId' in parents and
            trashed=false
        """.trimIndent().replace("\n", " ")
    }

    private fun toDriveFile(metadata: FileMetadata, parentFile: DriveFile? = null): DriveFile {
        return DriveFile(
            id = metadata.id,
            revisionId = metadata.headRevisionId ?: "",
            name = metadata.name,
            mimeType = metadata.mimeType,
            size = metadata.size,
            link = metadata.webViewLink,
            parent = parentFile,
            properties = metadata.appProperties ?: emptyMap()
        )
    }
}
