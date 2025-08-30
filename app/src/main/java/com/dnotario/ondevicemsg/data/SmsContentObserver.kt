package com.dnotario.ondevicemsg.data

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class SmsContentObserver(
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val TAG = "SmsContentObserver"
        private val SMS_URI = Uri.parse("content://sms")
        private val MMS_URI = Uri.parse("content://mms")
        private val MMS_SMS_URI = Uri.parse("content://mms-sms/")
    }
    
    fun observeChanges(): Flow<Unit> = callbackFlow {
        Log.d(TAG, "Starting content observation")
        
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Content changed: $uri")
                trySend(Unit)
            }
        }
        
        // Register for SMS changes
        contentResolver.registerContentObserver(
            SMS_URI,
            true, // Notify for descendant URIs
            observer
        )
        
        // Register for MMS changes
        contentResolver.registerContentObserver(
            MMS_URI,
            true,
            observer
        )
        
        // Register for combined MMS-SMS changes
        contentResolver.registerContentObserver(
            MMS_SMS_URI,
            true,
            observer
        )
        
        Log.d(TAG, "Content observers registered")
        
        // Send initial event to load data
        trySend(Unit)
        
        awaitClose {
            Log.d(TAG, "Unregistering content observers")
            contentResolver.unregisterContentObserver(observer)
        }
    }
}