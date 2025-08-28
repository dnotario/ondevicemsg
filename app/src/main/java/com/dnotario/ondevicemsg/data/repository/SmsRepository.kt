package com.dnotario.ondevicemsg.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.dnotario.ondevicemsg.data.models.Conversation
import com.dnotario.ondevicemsg.data.models.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Helper data class for intermediate storage
private data class ConversationData(
    val threadId: Long,
    val address: String,
    val date: Long,
    val snippet: String,
    val messageCount: Int,
    val unreadCount: Int,
    val lastMessageIsOutgoing: Boolean
)

class SmsRepository(private val context: Context) {
    
    private val contentResolver: ContentResolver = context.contentResolver
    private val contactCache = mutableMapOf<String, String>()
    
    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val conversations = mutableListOf<Conversation>()
        
        try {
            // Get all SMS messages, we'll group them ourselves
            val uri = Uri.parse("content://sms")
            val projection = arrayOf(
                "thread_id",
                "address", 
                "body",
                "date",
                "type",
                "read"
            )
            
            val cursor = contentResolver.query(
                uri,
                projection,
                null,
                null,
                "date DESC"
            )
            
            cursor?.use { 
                val phoneNumbers = mutableSetOf<String>()
                val threadDataMap = mutableMapOf<Long, ConversationData>()
                val threadIds = mutableSetOf<Long>()
                
                while (it.moveToNext()) {
                    val threadId = it.getLong(it.getColumnIndexOrThrow("thread_id"))
                    
                    // Only process each thread once (we get the most recent message)
                    if (threadId in threadIds) continue
                    threadIds.add(threadId)
                    
                    val address = it.getString(it.getColumnIndexOrThrow("address")) ?: continue
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))
                    val type = it.getInt(it.getColumnIndexOrThrow("type"))
                    val isRead = it.getInt(it.getColumnIndexOrThrow("read")) == 1
                    
                    phoneNumbers.add(address)
                    
                    threadDataMap[threadId] = ConversationData(
                        threadId = threadId,
                        address = address,
                        date = date,
                        snippet = body,
                        messageCount = 0, // We'll skip this for speed
                        unreadCount = if (!isRead && type == Message.TYPE_INBOX) 1 else 0,
                        lastMessageIsOutgoing = type == Message.TYPE_SENT
                    )
                    
                    // Only load first 15 conversations for speed
                    if (threadDataMap.size >= 15) break
                }
                
                // Batch load contact names (this is fast)
                val contactNames = batchLoadContactNames(phoneNumbers)
                
                // Build conversation list
                threadDataMap.values.sortedByDescending { it.date }.forEach { data ->
                    conversations.add(
                        Conversation(
                            threadId = data.threadId,
                            address = data.address,
                            contactName = contactNames[data.address],
                            messageCount = data.messageCount,
                            unreadCount = data.unreadCount,
                            lastMessageTime = data.date,
                            lastMessageText = data.snippet,
                            lastMessageIsOutgoing = data.lastMessageIsOutgoing
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading conversations", e)
        }
        
        conversations
    }
    
    suspend fun getMessagesForConversation(threadId: Long): List<Message> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<Message>()
        
        try {
            val uri = Uri.parse("content://sms")
            val projection = arrayOf(
                "_id",
                "thread_id",
                "address",
                "body",
                "date",
                "read",
                "type"
            )
            
            val cursor = contentResolver.query(
                uri,
                projection,
                "thread_id = ?",
                arrayOf(threadId.toString()),
                "date DESC"
            )
            
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    if (address == null) continue
                    
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))
                    val isRead = it.getInt(it.getColumnIndexOrThrow("read")) == 1
                    val type = it.getInt(it.getColumnIndexOrThrow("type"))
                    
                    messages.add(
                        Message(
                            id = id,
                            threadId = threadId,
                            address = address,
                            body = body,
                            date = date,
                            isRead = isRead,
                            isOutgoing = type == Message.TYPE_SENT
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading messages for thread $threadId", e)
        }
        
        messages
    }
    
    suspend fun markMessagesAsRead(threadId: Long) = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put("read", 1)
            }
            
            val uri = Uri.parse("content://sms")
            contentResolver.update(
                uri,
                values,
                "thread_id = ? AND read = 0",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error marking messages as read", e)
        }
    }
    
    fun sendSms(phoneNumber: String, message: String) {
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            smsManager.sendTextMessage(
                phoneNumber,
                null,
                message,
                null,
                null
            )
            Log.d("SmsRepository", "SMS sent to $phoneNumber")
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error sending SMS", e)
        }
    }
    
    private fun getAddressForThread(threadId: Long): String? {
        try {
            val uri = Uri.parse("content://sms")
            val cursor = contentResolver.query(
                uri,
                arrayOf("address"),
                "thread_id = ?",
                arrayOf(threadId.toString()),
                "date DESC LIMIT 1"
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error getting address for thread $threadId", e)
        }
        return null
    }
    
    private fun checkLastMessageIsOutgoing(threadId: Long): Boolean {
        try {
            val uri = Uri.parse("content://sms")
            val cursor = contentResolver.query(
                uri,
                arrayOf("type"),
                "thread_id = ?",
                arrayOf(threadId.toString()),
                "date DESC LIMIT 1"
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val type = it.getInt(0)
                    return type == Message.TYPE_SENT
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error checking last message type", e)
        }
        return false
    }
    
    private fun getUnreadCount(threadId: Long): Int {
        try {
            val uri = Uri.parse("content://sms")
            val cursor = contentResolver.query(
                uri,
                arrayOf("COUNT(*) as count"),
                "thread_id = ? AND read = 0 AND type = ${Message.TYPE_INBOX}",
                arrayOf(threadId.toString()),
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getInt(0)
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error getting unread count", e)
        }
        return 0
    }
    
    private fun batchLoadContactNames(phoneNumbers: Set<String>): Map<String, String> {
        val contactNames = mutableMapOf<String, String>()
        
        // Return cached names first
        phoneNumbers.forEach { number ->
            contactCache[number]?.let { 
                contactNames[number] = it 
            }
        }
        
        // Get uncached numbers
        val uncachedNumbers = phoneNumbers - contactNames.keys
        if (uncachedNumbers.isEmpty()) return contactNames
        
        // For phone numbers not found in batch, try individual lookups
        uncachedNumbers.forEach { phoneNumber ->
            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber)
                )
                
                val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
                
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        contactNames[phoneNumber] = name
                        contactCache[phoneNumber] = name
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsRepository", "Error loading contact name for $phoneNumber", e)
            }
        }
        
        return contactNames
    }
    
    private fun getContactName(phoneNumber: String): String? {
        // Check cache first
        contactCache[phoneNumber]?.let { return it }
        
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    )
                    // Cache the result
                    contactCache[phoneNumber] = name
                    return name
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error getting contact name for $phoneNumber", e)
        }
        
        return null
    }
}