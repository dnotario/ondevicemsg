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
    
    suspend fun getUnreadMessagesForConversation(threadId: Long): List<Message> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<Message>()
        Log.d("SmsRepository", "Getting unread messages for thread $threadId")
        
        // Get unread SMS messages
        try {
            val uri = Uri.parse("content://sms")
            val projection = arrayOf(
                "_id",
                "address",
                "body",
                "date",
                "read",
                "type"
            )
            
            val cursor = contentResolver.query(
                uri,
                projection,
                "thread_id = ? AND read = 0 AND type = ${Message.TYPE_INBOX}",
                arrayOf(threadId.toString()),
                "date DESC"
            )
            Log.d("SmsRepository", "SMS query returned ${cursor?.count ?: 0} unread messages")
            
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    if (address == null) continue
                    
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))
                    
                    messages.add(
                        Message(
                            id = id,
                            threadId = threadId,
                            address = address,
                            body = body,
                            date = date,
                            isRead = false,
                            isOutgoing = false,
                            isMms = false,
                            hasImage = false,
                            imageUri = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading unread SMS messages for thread $threadId", e)
        }
        
        // Also get unread MMS messages
        try {
            val mmsUri = Uri.parse("content://mms")
            val mmsCursor = contentResolver.query(
                mmsUri,
                arrayOf("_id", "thread_id", "date", "read", "msg_box"),
                "thread_id = ? AND read = 0 AND msg_box = 1",  // msg_box = 1 for inbox
                arrayOf(threadId.toString()),
                "date DESC"
            )
            
            mmsCursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val date = it.getLong(it.getColumnIndexOrThrow("date")) * 1000 // MMS date is in seconds
                    
                    // Get MMS parts
                    val text = getMmsText(id)
                    val imageUri = getMmsImageUri(id)
                    val address = getMmsAddress(id, false)
                    
                    messages.add(
                        Message(
                            id = id,
                            threadId = threadId,
                            address = address,
                            body = text ?: "",
                            date = date,
                            isRead = false,
                            isOutgoing = false,
                            isMms = true,
                            hasImage = imageUri != null,
                            imageUri = imageUri
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading unread MMS messages for thread $threadId", e)
        }
        
        // Sort by date
        messages.sortByDescending { it.date }
        Log.d("SmsRepository", "Returning ${messages.size} total unread messages for thread $threadId")
        messages
    }
    
    suspend fun getLastMessageForConversation(threadId: Long): Message? = withContext(Dispatchers.IO) {
        var lastSms: Message? = null
        var lastMms: Message? = null
        
        // Get last SMS
        try {
            val uri = Uri.parse("content://sms")
            val cursor = contentResolver.query(
                uri,
                arrayOf("_id", "address", "body", "date", "read", "type"),
                "thread_id = ?",
                arrayOf(threadId.toString()),
                "date DESC LIMIT 1"
            )
            
            cursor?.use {
                if (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val address = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))
                    val isRead = it.getInt(it.getColumnIndexOrThrow("read")) == 1
                    val type = it.getInt(it.getColumnIndexOrThrow("type"))
                    
                    lastSms = Message(
                        id = id,
                        threadId = threadId,
                        address = address,
                        body = body,
                        date = date,
                        isRead = isRead,
                        isOutgoing = type == Message.TYPE_SENT,
                        isMms = false,
                        hasImage = false,
                        imageUri = null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading last SMS for thread $threadId", e)
        }
        
        // Get last MMS
        try {
            val mmsUri = Uri.parse("content://mms")
            val mmsCursor = contentResolver.query(
                mmsUri,
                arrayOf("_id", "thread_id", "date", "read", "msg_box"),
                "thread_id = ?",
                arrayOf(threadId.toString()),
                "date DESC LIMIT 1"
            )
            
            mmsCursor?.use {
                if (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val date = it.getLong(it.getColumnIndexOrThrow("date")) * 1000
                    val isRead = it.getInt(it.getColumnIndexOrThrow("read")) == 1
                    val msgBox = it.getInt(it.getColumnIndexOrThrow("msg_box"))
                    val isOutgoing = msgBox == 2
                    
                    val text = getMmsText(id)
                    val imageUri = getMmsImageUri(id)
                    val address = getMmsAddress(id, isOutgoing)
                    
                    lastMms = Message(
                        id = id,
                        threadId = threadId,
                        address = address,
                        body = text ?: "",
                        date = date,
                        isRead = isRead,
                        isOutgoing = isOutgoing,
                        isMms = true,
                        hasImage = imageUri != null,
                        imageUri = imageUri
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading last MMS for thread $threadId", e)
        }
        
        // Return the most recent
        val sms = lastSms
        val mms = lastMms
        when {
            sms == null -> mms
            mms == null -> sms
            sms.date > mms.date -> sms
            else -> mms
        }
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
                            isOutgoing = type == Message.TYPE_SENT,
                            isMms = false,
                            hasImage = false,
                            imageUri = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading SMS messages for thread $threadId", e)
        }
        
        // Also get MMS messages for this thread
        try {
            val mmsMessages = getMmsMessagesForThread(threadId)
            Log.d("SmsRepository", "Found ${mmsMessages.size} MMS messages for thread $threadId")
            messages.addAll(mmsMessages)
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading MMS messages for thread $threadId", e)
        }
        
        // Sort combined messages by date
        messages.sortByDescending { it.date }
        messages
    }
    
    private suspend fun getMmsMessagesForThread(threadId: Long): List<Message> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<Message>()
        
        try {
            val uri = Uri.parse("content://mms")
            val cursor = contentResolver.query(
                uri,
                arrayOf("_id", "thread_id", "date", "read", "msg_box"),
                "thread_id = ?",
                arrayOf(threadId.toString()),
                "date DESC"
            )
            
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val date = it.getLong(it.getColumnIndexOrThrow("date")) * 1000 // MMS date is in seconds
                    val isRead = it.getInt(it.getColumnIndexOrThrow("read")) == 1
                    val msgBox = it.getInt(it.getColumnIndexOrThrow("msg_box"))
                    val isOutgoing = msgBox == 2 // 2 = sent
                    
                    // Get MMS parts (text and images)
                    val text = getMmsText(id)
                    val imageUri = getMmsImageUri(id)
                    
                    // Get address
                    val address = getMmsAddress(id, isOutgoing)
                    
                    messages.add(
                        Message(
                            id = id,
                            threadId = threadId,
                            address = address,
                            body = text ?: "",
                            date = date,
                            isRead = isRead,
                            isOutgoing = isOutgoing,
                            isMms = true,
                            hasImage = imageUri != null,
                            imageUri = imageUri
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading MMS messages", e)
        }
        
        messages
    }
    
    private fun getMmsText(mmsId: Long): String? {
        val uri = Uri.parse("content://mms/part")
        val cursor = contentResolver.query(
            uri,
            arrayOf("_id", "ct", "text"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val ct = it.getString(it.getColumnIndexOrThrow("ct"))
                if (ct == "text/plain") {
                    return it.getString(it.getColumnIndexOrThrow("text"))
                }
            }
        }
        return null
    }
    
    private fun getMmsImageUri(mmsId: Long): String? {
        val uri = Uri.parse("content://mms/part")
        val cursor = contentResolver.query(
            uri,
            arrayOf("_id", "ct"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val ct = it.getString(it.getColumnIndexOrThrow("ct"))
                if (ct?.startsWith("image/") == true) {
                    val partId = it.getLong(it.getColumnIndexOrThrow("_id"))
                    return "content://mms/part/$partId"
                }
            }
        }
        return null
    }
    
    private fun getMmsAddress(mmsId: Long, isOutgoing: Boolean): String {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val cursor = contentResolver.query(
            uri,
            arrayOf("address", "type"),
            null,
            null,
            null
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val type = it.getInt(it.getColumnIndexOrThrow("type"))
                // type 137 = from, type 151 = to
                if ((isOutgoing && type == 151) || (!isOutgoing && type == 137)) {
                    return it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                }
            }
        }
        return ""
    }
    
    suspend fun markMessagesAsRead(threadId: Long) = withContext(Dispatchers.IO) {
        try {
            Log.d("SmsRepository", "Starting to mark messages as read for thread $threadId")
            
            // Mark SMS messages as read
            val values = android.content.ContentValues().apply {
                put("read", 1)
            }
            
            val smsUri = Uri.parse("content://sms")
            val smsCount = contentResolver.update(
                smsUri,
                values,
                "thread_id = ? AND read = 0",
                arrayOf(threadId.toString())
            )
            Log.d("SmsRepository", "Marked $smsCount SMS messages as read for thread $threadId")
            
            // Also mark MMS messages as read
            val mmsUri = Uri.parse("content://mms")
            val mmsCount = contentResolver.update(
                mmsUri,
                values,
                "thread_id = ? AND read = 0",
                arrayOf(threadId.toString())
            )
            Log.d("SmsRepository", "Marked $mmsCount MMS messages as read for thread $threadId")
            
            if (smsCount == 0 && mmsCount == 0) {
                Log.w("SmsRepository", "Warning: No messages were marked as read for thread $threadId")
            }
        } catch (e: SecurityException) {
            Log.e("SmsRepository", "Security exception marking messages as read - missing WRITE_SMS permission?", e)
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error marking messages as read for thread $threadId", e)
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