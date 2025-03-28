package io.muun.apollo.data.apis

/**
 * Represents a file in Google Drive with its metadata.
 *
 * @property id The unique identifier of the file in Google Drive
 * @property revisionId The current revision identifier of the file
 * @property name The name of the file
 * @property mimeType The MIME type of the file
 * @property size The size of the file in bytes
 * @property link A web link to access the file
 * @property parent The parent folder of this file, if any
 * @property properties Additional metadata properties as key-value pairs
 */
data class DriveFile(
    val id: String,
    val revisionId: String,
    val name: String,
    val mimeType: String,
    val size: Long,  // Changed from Int to Long for large files
    val link: String,
    val parent: DriveFile? = null,
    val properties: Map<String, String> = emptyMap()
) {
    /**
     * Checks if this file is a Google Docs format file.
     */
    fun isGoogleDoc(): Boolean = mimeType.startsWith("application/vnd.google-apps.")

    /**
     * Gets the property value or null if not found.
     */
    fun getProperty(key: String): String? = properties[key]

    companion object {
        /**
         * Creates a minimal DriveFile with required fields.
         */
        fun minimal(
            id: String,
            revisionId: String,
            name: String,
            mimeType: String,
            size: Long,
            link: String
        ) = DriveFile(
            id = id,
            revisionId = revisionId,
            name = name,
            mimeType = mimeType,
            size = size,
            link = link
        )
    }
}