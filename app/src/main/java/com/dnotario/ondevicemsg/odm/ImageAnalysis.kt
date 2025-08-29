package com.dnotario.ondevicemsg.odm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.genai.imagedescription.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

/**
 * Image analysis service for generating AI descriptions of images using ML Kit
 */
class ImageAnalysis(private val context: Context) {
    
    companion object {
        private const val TAG = "ODM_ImageAnalysis"
    }
    
    private var imageDescriber: ImageDescriber? = null
    private var isInitialized = false
    
    /**
     * Initialize the image describer
     */
    suspend fun initialize() {
        if (isInitialized) return
        
        try {
            val options = ImageDescriberOptions.builder(context).build()
            imageDescriber = ImageDescription.getClient(options)
            isInitialized = true
            Log.d(TAG, "Image describer initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize image describer", e)
        }
    }
    
    /**
     * Generate a description for an image from a URI
     * @param imageUri The URI of the image to describe
     * @return The description text, or null if failed
     */
    suspend fun describeImage(imageUri: Uri): String? {
        if (!isInitialized) {
            initialize()
        }
        
        if (!isInitialized || imageDescriber == null) {
            Log.e(TAG, "Image describer not initialized")
            return null
        }
        
        return try {
            val bitmap = loadBitmapFromUri(imageUri)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from URI: $imageUri")
                return null
            }
            
            describeImage(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to describe image from URI", e)
            null
        }
    }
    
    /**
     * Generate a description for a bitmap image
     * @param bitmap The bitmap image to describe
     * @return The description text, or null if failed
     */
    suspend fun describeImage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            initialize()
        }
        
        if (!isInitialized || imageDescriber == null) {
            Log.e(TAG, "Image describer not initialized")
            return@withContext null
        }
        
        try {
            // Create the request
            val request = ImageDescriptionRequest.builder(bitmap).build()
            
            // Run inference and get the future
            val future: ListenableFuture<ImageDescriptionResult>? = imageDescriber?.runInference(request)
            
            if (future == null) {
                Log.e(TAG, "Failed to start inference")
                return@withContext null
            }
            
            // Wait for the future to complete and get the result
            val result = try {
                future.get() // This blocks until the result is ready
            } catch (e: ExecutionException) {
                Log.e(TAG, "Inference execution failed", e)
                return@withContext null
            }
            
            val description = result.description
            Log.d(TAG, "Image description: $description")
            
            description.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Error during image inference", e)
            null
        }
    }
    
    /**
     * Check if the model is ready
     */
    fun isModelReady(): Boolean {
        return isInitialized && imageDescriber != null
    }
    
    /**
     * Release resources
     */
    fun release() {
        imageDescriber?.close()
        imageDescriber = null
        isInitialized = false
        Log.d(TAG, "Image describer released")
    }
    
    /**
     * Load a bitmap from a content URI
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: $uri", e)
            null
        }
    }
}