package acr.browser.lightning.mediacatcher

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class MediaType { IMAGE, VIDEO, AUDIO, OTHER }

@Parcelize
data class MediaItem(
    val id: Long = System.currentTimeMillis(),
    val url: String,
    val localPath: String,
    val mimeType: String,
    val mediaType: MediaType,
    val sourcePageUrl: String,
    val fileSizeBytes: Long,
    val capturedAt: Long = System.currentTimeMillis(),
    val fileName: String
) : Parcelable
