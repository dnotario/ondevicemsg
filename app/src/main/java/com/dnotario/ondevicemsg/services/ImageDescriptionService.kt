package com.dnotario.ondevicemsg.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.genai.imagedescription.*
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Service for generating AI descriptions of images using ML Kit
 */
class ImageDescriptionService(private val context: Context) {
    
    private var imageDescriber: ImageDescriber? = null
    private var isInitialized = false
    
    companion object {
        private const val TAG = "ImageDescription"
    }
    
    /**
     * Initialize the image describer
     */
    suspend fun initialize() {
        if (isInitialized) return
        
        try {
            val options = ImageDescriberOptions.builder(context).build()
            imageDescriber = ImageDescription.getClient(options)
            isInitialized = true
            Log.d(TAG, "Image describer client created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize image describer", e)
        }
    }
    
    /**
     * Generate a description for an image from a URI
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
            // Load bitmap from URI
            val bitmap = loadBitmapFromUri(imageUri)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from URI: $imageUri")
                return null
            }
            
            // Generate description
            describeImage(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to describe image", e)
            null
        }
    }
    
    /**
     * Generate a description for a bitmap image
     */
    suspend fun describeImage(bitmap: Bitmap): String? {
        if (!isInitialized || imageDescriber == null) {
            Log.e(TAG, "Image describer not initialized")
            return null
        }
        
        return try {
            // Create the request
            val request = ImageDescriptionRequest.builder(bitmap).build()
            
            // Collect the streaming response
            val descriptionBuilder = StringBuilder()
            
            // Run inference with streaming callback
            val inferenceResult = imageDescriber?.runInference(request) { outputText ->
                Log.d(TAG, "Received description chunk: $outputText")
                descriptionBuilder.append(outputText)
            }
            
            // Wait for the inference to complete
            inferenceResult?.await()
            
            val finalDescription = descriptionBuilder.toString().trim()
            Log.d(TAG, "Final image description: $finalDescription")
            
            finalDescription.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Error during image inference", e)
            null
        }
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
    
    /**
     * Clean up resources
     */
    fun close() {
        imageDescriber?.close()
        imageDescriber = null
        isInitialized = false
    }
}