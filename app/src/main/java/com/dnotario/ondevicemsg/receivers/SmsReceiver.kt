package com.dnotario.ondevicemsg.receivers

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only handle SMS_DELIVER when we're the default SMS app
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (message in messages) {
            Log.d("SmsReceiver", "Received SMS from: ${message.displayOriginatingAddress}")
            
            // Save the message to the SMS database
            val values = ContentValues().apply {
                put("address", message.displayOriginatingAddress)
                put("body", message.messageBody)
                put("date", System.currentTimeMillis())
                put("read", 0) // Mark as unread
                put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            
            try {
                val uri = context.contentResolver.insert(
                    Uri.parse("content://sms/inbox"),
                    values
                )
                Log.d("SmsReceiver", "SMS saved to database: $uri")
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Failed to save SMS", e)
            }
        }
    }
}