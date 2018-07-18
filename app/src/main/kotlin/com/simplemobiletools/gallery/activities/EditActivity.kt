package com.simplemobiletools.gallery.activities

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Color
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.OTG_PATH
import com.simplemobiletools.commons.helpers.PERMISSION_WRITE_STORAGE
import com.simplemobiletools.commons.helpers.REAL_FILE_PATH
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.gallery.R
import com.simplemobiletools.gallery.dialogs.ResizeDialog
import com.simplemobiletools.gallery.dialogs.SaveAsDialog
import com.simplemobiletools.gallery.extensions.config
import com.simplemobiletools.gallery.extensions.openEditor
import com.theartofdev.edmodo.cropper.CropImageView
import kotlinx.android.synthetic.main.activity_edit.*
import kotlinx.android.synthetic.main.bottom_editor_actions.*
import java.io.*

class EditActivity : SimpleActivity(), CropImageView.OnCropImageCompleteListener {
    private val ASPECT_X = "aspectX"
    private val ASPECT_Y = "aspectY"
    private val CROP = "crop"

    private val ASPECT_RATIO_ANY = 0
    private val ASPECT_RATIO_ONE_ONE = 1
    private val ASPECT_RATIO_FOUR_THREE = 2
    private val ASPECT_RATIO_SIXTEEN_NINE = 3

    // constants for bottom primary action groups
    private val PRIMARY_NONE = 0
    private val PRIMARY_ASPECT_RATIO = 1

    private lateinit var uri: Uri
    private lateinit var saveUri: Uri
    private var resizeWidth = 0
    private var resizeHeight = 0
    private var currPrimaryAction = 0
    private var isCropIntent = false
    private var isEditingWithThirdParty = false
    private var currentAspectRatio = ASPECT_RATIO_ANY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        handlePermission(PERMISSION_WRITE_STORAGE) {
            if (it) {
                initEditActivity()
            } else {
                toast(R.string.no_storage_permissions)
                finish()
            }
        }
    }

    private fun initEditActivity() {
        if (intent.data == null) {
            toast(R.string.invalid_image_path)
            finish()
            return
        }

        uri = intent.data
        if (uri.scheme != "file" && uri.scheme != "content") {
            toast(R.string.unknown_file_location)
            finish()
            return
        }

        if (intent.extras?.containsKey(REAL_FILE_PATH) == true) {
            val realPath = intent.extras.getString(REAL_FILE_PATH)
            uri = when {
                realPath.startsWith(OTG_PATH) -> uri
                realPath.startsWith("file:/") -> Uri.parse(realPath)
                else -> Uri.fromFile(File(realPath))
            }
        } else {
            (getRealPathFromURI(uri))?.apply {
                uri = Uri.fromFile(File(this))
            }
        }

        saveUri = when {
            intent.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true -> intent.extras!!.get(MediaStore.EXTRA_OUTPUT) as Uri
            else -> uri
        }

        isCropIntent = intent.extras?.get(CROP) == "true"

        crop_image_view.apply {
            setOnCropImageCompleteListener(this@EditActivity)
            setImageUriAsync(uri)

            if (isCropIntent && shouldCropSquare()) {
                currentAspectRatio = ASPECT_RATIO_ONE_ONE
                setFixedAspectRatio(true)
                bottom_aspect_ratio.beGone()
            }
        }

        setupBottomActions()
    }

    override fun onResume() {
        super.onResume()
        isEditingWithThirdParty = false
    }

    override fun onStop() {
        super.onStop()
        if (isEditingWithThirdParty) {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.save_as -> crop_image_view.getCroppedImageAsync()
            R.id.edit -> editWith()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun setupBottomActions() {
        bottom_rotate.setOnClickListener {
            crop_image_view.rotateImage(90)
        }

        bottom_resize.beGoneIf(isCropIntent)
        bottom_resize.setOnClickListener {
            resizeImage()
        }

        bottom_flip_horizontally.setOnClickListener {
            crop_image_view.flipImageHorizontally()
        }

        bottom_flip_vertically.setOnClickListener {
            crop_image_view.flipImageVertically()
        }

        bottom_aspect_ratio.setOnClickListener {
            currPrimaryAction = if (currPrimaryAction == PRIMARY_ASPECT_RATIO) 0 else PRIMARY_ASPECT_RATIO
            updatePrimaryActions()
        }
    }

    private fun updatePrimaryActions() {
        val primaryColor = config.primaryColor
        arrayOf(bottom_aspect_ratio).forEach {
            it.applyColorFilter(Color.WHITE)
        }

        val primaryActionView = when (currPrimaryAction) {
            PRIMARY_ASPECT_RATIO -> bottom_aspect_ratio
            else -> null
        }

        primaryActionView?.applyColorFilter(primaryColor)
    }

    private fun resizeImage() {
        val point = getAreaSize()
        if (point == null) {
            toast(R.string.unknown_error_occurred)
            return
        }

        ResizeDialog(this, point) {
            resizeWidth = it.x
            resizeHeight = it.y
            crop_image_view.getCroppedImageAsync()
        }
    }

    private fun shouldCropSquare(): Boolean {
        val extras = intent.extras
        return if (extras != null && extras.containsKey(ASPECT_X) && extras.containsKey(ASPECT_Y)) {
            extras.getInt(ASPECT_X) == extras.getInt(ASPECT_Y)
        } else {
            false
        }
    }

    private fun getAreaSize(): Point? {
        val rect = crop_image_view.cropRect ?: return null
        val rotation = crop_image_view.rotatedDegrees
        return if (rotation == 0 || rotation == 180) {
            Point(rect.width(), rect.height())
        } else {
            Point(rect.height(), rect.width())
        }
    }

    override fun onCropImageComplete(view: CropImageView, result: CropImageView.CropResult) {
        if (result.error == null) {
            if (isCropIntent) {
                if (saveUri.scheme == "file") {
                    saveBitmapToFile(result.bitmap, saveUri.path)
                } else {
                    var inputStream: InputStream? = null
                    var outputStream: OutputStream? = null
                    try {
                        val stream = ByteArrayOutputStream()
                        result.bitmap.compress(CompressFormat.JPEG, 100, stream)
                        inputStream = ByteArrayInputStream(stream.toByteArray())
                        outputStream = contentResolver.openOutputStream(saveUri)
                        inputStream.copyTo(outputStream)
                    } finally {
                        inputStream?.close()
                        outputStream?.close()
                    }

                    Intent().apply {
                        data = saveUri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        setResult(RESULT_OK, this)
                    }
                    finish()
                }
            } else if (saveUri.scheme == "file") {
                SaveAsDialog(this, saveUri.path, true) {
                    saveBitmapToFile(result.bitmap, it)
                }
            } else if (saveUri.scheme == "content") {
                var newPath = applicationContext.getRealPathFromURI(saveUri) ?: ""
                var shouldAppendFilename = true
                if (newPath.isEmpty()) {
                    val filename = applicationContext.getFilenameFromContentUri(saveUri) ?: ""
                    if (filename.isNotEmpty()) {
                        val path = if (intent.extras?.containsKey(REAL_FILE_PATH) == true) intent.getStringExtra(REAL_FILE_PATH).getParentPath() else internalStoragePath
                        newPath = "$path/$filename"
                        shouldAppendFilename = false
                    }
                }

                if (newPath.isEmpty()) {
                    newPath = "$internalStoragePath/${getCurrentFormattedDateTime()}.${saveUri.toString().getFilenameExtension()}"
                    shouldAppendFilename = false
                }

                SaveAsDialog(this, newPath, shouldAppendFilename) {
                    saveBitmapToFile(result.bitmap, it)
                }
            } else {
                toast(R.string.unknown_file_location)
            }
        } else {
            toast("${getString(R.string.image_editing_failed)}: ${result.error.message}")
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, path: String) {
        try {
            Thread {
                val file = File(path)
                val fileDirItem = FileDirItem(path, path.getFilenameFromPath())
                getFileOutputStream(fileDirItem, true) {
                    if (it != null) {
                        saveBitmap(file, bitmap, it)
                    } else {
                        toast(R.string.image_editing_failed)
                    }
                }
            }.start()
        } catch (e: Exception) {
            showErrorToast(e)
        } catch (e: OutOfMemoryError) {
            toast(R.string.out_of_memory_error)
        }
    }

    private fun saveBitmap(file: File, bitmap: Bitmap, out: OutputStream) {
        toast(R.string.saving)
        if (resizeWidth > 0 && resizeHeight > 0) {
            val resized = Bitmap.createScaledBitmap(bitmap, resizeWidth, resizeHeight, false)
            resized.compress(file.absolutePath.getCompressionFormat(), 90, out)
        } else {
            bitmap.compress(file.absolutePath.getCompressionFormat(), 90, out)
        }
        setResult(Activity.RESULT_OK, intent)
        scanFinalPath(file.absolutePath)
        out.close()
    }

    private fun editWith() {
        openEditor(uri.toString())
        isEditingWithThirdParty = true
    }

    private fun scanFinalPath(path: String) {
        scanPathRecursively(path) {
            setResult(Activity.RESULT_OK, intent)
            toast(R.string.file_saved)
            finish()
        }
    }
}
