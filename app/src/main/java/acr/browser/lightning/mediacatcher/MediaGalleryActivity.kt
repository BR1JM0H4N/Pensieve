package acr.browser.lightning.mediacatcher

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import acr.browser.lightning.BuildConfig
import acr.browser.lightning.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * MediaGalleryActivity
 * Full-screen gallery showing captured images / videos / audio.
 * Tap → preview. Long-press → share/delete menu.
 */
class MediaGalleryActivity : AppCompatActivity() {

    private lateinit var repo: MediaRepository
    private lateinit var gridView: GridView
    private lateinit var tabRow: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var countText: TextView

    private var currentFilter: MediaType? = null
    private var currentItems: List<MediaItem> = emptyList()
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = MediaRepository(this)

        setContentView(buildLayout())
        supportActionBar?.title = "Media Vault"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        showFilter(null) // show all by default
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Delete All").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) confirmDeleteAll()
        return super.onOptionsItemSelected(item)
    }

    private fun showFilter(type: MediaType?) {
        currentFilter = type
        currentItems = if (type == null) repo.loadAll()
                       else repo.loadAll().filter { it.mediaType == type }
        updateTabHighlight()
        refreshGrid()
    }

    private fun refreshGrid() {
        emptyText.visibility = if (currentItems.isEmpty()) View.VISIBLE else View.GONE
        gridView.adapter = MediaGridAdapter()

        val all   = repo.loadAll()
        val imgs  = all.count { it.mediaType == MediaType.IMAGE }
        val vids  = all.count { it.mediaType == MediaType.VIDEO }
        val auds  = all.count { it.mediaType == MediaType.AUDIO }
        countText.text = "All: ${all.size}  •  🖼 $imgs  •  🎬 $vids  •  🎵 $auds"
    }

    private fun updateTabHighlight() {
        val tabs = listOf(
            tabRow.getChildAt(0) to null,
            tabRow.getChildAt(1) to MediaType.IMAGE,
            tabRow.getChildAt(2) to MediaType.VIDEO,
            tabRow.getChildAt(3) to MediaType.AUDIO
        )
        tabs.forEach { (view, type) ->
            (view as TextView).setBackgroundColor(
                if (type == currentFilter) Color.parseColor("#1565C0")
                else Color.parseColor("#1E88E5")
            )
        }
    }

    // ── Preview ────────────────────────────────────────────────────────────────

    private fun previewItem(item: MediaItem) {
        val file = File(item.localPath)
        if (!file.exists()) {
            Toast.makeText(this, "File missing", Toast.LENGTH_SHORT).show()
            return
        }
        when (item.mediaType) {
            MediaType.IMAGE -> previewImage(item, file)
            MediaType.VIDEO -> openExternal(item, file)
            MediaType.AUDIO -> playAudio(item, file)
            else            -> openExternal(item, file)
        }
    }

    private fun previewImage(item: MediaItem, file: File) {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: run {
            openExternal(item, file); return
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setView(iv)
            .setPositiveButton("Share") { _, _ -> shareItem(item) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun playAudio(item: MediaItem, file: File) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
        Toast.makeText(this, "▶ Playing: ${item.fileName}", Toast.LENGTH_SHORT).show()
    }

    private fun openExternal(item: MediaItem, file: File) {
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try { startActivity(intent) }
        catch (_: Exception) {
            Toast.makeText(this, "No app to open ${item.mimeType}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareItem(item: MediaItem) {
        val file = File(item.localPath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(Intent.createChooser(intent, "Share ${item.fileName}"))
    }

    private fun showItemMenu(item: MediaItem) {
        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setMessage(
                "Type: ${item.mediaType.name}\n" +
                "Size: ${formatSize(item.fileSizeBytes)}\n" +
                "From: ${item.sourcePageUrl.take(60)}\n" +
                "Date: ${formatDate(item.capturedAt)}"
            )
            .setPositiveButton("Share")  { _, _ -> shareItem(item) }
            .setNeutralButton("Open URL") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
            }
            .setNegativeButton("Delete") { _, _ -> deleteItem(item) }
            .show()
    }

    private fun deleteItem(item: MediaItem) {
        File(item.localPath).delete()
        repo.delete(item)
        showFilter(currentFilter)
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("Delete All Media?")
            .setMessage("This will remove all captured files from disk.")
            .setPositiveButton("Delete All") { _, _ ->
                repo.loadAll().forEach { File(it.localPath).delete() }
                repo.deleteAll()
                showFilter(null)
                Toast.makeText(this, "All deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // ── Grid Adapter ───────────────────────────────────────────────────────────

    inner class MediaGridAdapter : BaseAdapter() {
        override fun getCount() = currentItems.size
        override fun getItem(pos: Int) = currentItems[pos]
        override fun getItemId(pos: Int) = currentItems[pos].id

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val item = currentItems[pos]
            val frame = FrameLayout(this@MediaGalleryActivity).apply {
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(120)
                )
                setBackgroundColor(Color.parseColor("#1A237E"))
                setPadding(4, 4, 4, 4)
            }

            val thumb = ImageView(this@MediaGalleryActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#283593"))
            }

            if (item.mediaType == MediaType.IMAGE) {
                val bmp = BitmapFactory.decodeFile(item.localPath)
                if (bmp != null) thumb.setImageBitmap(bmp)
                else thumb.setImageResource(android.R.drawable.ic_menu_gallery)
            } else {
                thumb.setImageResource(when (item.mediaType) {
                    MediaType.VIDEO -> android.R.drawable.ic_media_play
                    MediaType.AUDIO -> android.R.drawable.ic_media_play
                    else            -> android.R.drawable.ic_menu_save
                })
            }

            val badge = TextView(this@MediaGalleryActivity).apply {
                text = when (item.mediaType) {
                    MediaType.IMAGE -> "🖼"
                    MediaType.VIDEO -> "🎬"
                    MediaType.AUDIO -> "🎵"
                    else            -> "📄"
                } + " " + formatSize(item.fileSizeBytes)
                setTextColor(Color.WHITE)
                textSize = 9f
                setPadding(6, 2, 6, 2)
                setBackgroundColor(Color.parseColor("#AA000000"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            }

            frame.addView(thumb)
            frame.addView(badge)

            frame.setOnClickListener { previewItem(item) }
            frame.setOnLongClickListener { showItemMenu(item); true }

            return frame
        }
    }

    // ── Layout builder (no XML needed) ────────────────────────────────────────

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1B6E"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Tab row
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        listOf("All" to null, "Images" to MediaType.IMAGE,
               "Videos" to MediaType.VIDEO, "Audio" to MediaType.AUDIO)
            .forEach { (label, type) ->
                tabRow.addView(TextView(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, dpToPx(12), 0, dpToPx(12))
                    setBackgroundColor(Color.parseColor("#1E88E5"))
                    layoutParams = LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { showFilter(type) }
                })
            }

        // Count bar
        countText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 11f
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
        }

        // Empty state
        emptyText = TextView(this).apply {
            text = "No media captured yet.\nBrowse some websites and come back!"
            setTextColor(Color.parseColor("#7986CB"))
            textSize = 15f
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(60) }
        }

        // Grid
        gridView = GridView(this).apply {
            numColumns = 3
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            setVerticalSpacing(dpToPx(2))
            setHorizontalSpacing(dpToPx(2))
            setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            setBackgroundColor(Color.parseColor("#0D1B6E"))
        }

        root.addView(tabRow)
        root.addView(countText)
        root.addView(emptyText)
        root.addView(gridView)
        return root
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int) =
        (dp * resources.displayMetrics.density).toInt()

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024      -> "%.0f KB".format(bytes / 1024.0)
        else               -> "$bytes B"
    }

    private fun formatDate(ts: Long): String =
        SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(ts))

    companion object {
        fun launch(from: Activity) {
            from.startActivity(Intent(from, MediaGalleryActivity::class.java))
        }
    }
}
