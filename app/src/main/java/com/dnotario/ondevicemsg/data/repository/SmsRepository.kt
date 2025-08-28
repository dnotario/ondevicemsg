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

class SmsRepository(private val context: Context) {
    
    private val contentResolver: ContentResolver = context.contentResolver
    private val contactCache = mutableMapOf<String, String>()
    
    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val conversations = mutableMapOf<Long, Conversation>()
        
        try {
            // Query SMS inbox and sent messages
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
                null,
                null,
                "date DESC"
            )
            
            cursor?.use {
                val threadMessages = mutableMapOf<Long, MutableList<Message>>()
                
                while (it.moveToNext()) {
                    val threadId = it.getLong(it.getColumnIndexOrThrow("thread_id"))
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    if (address == null) continue
                    
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))
                    val isRead = it.getInt(it.getColumnIndexOrThrow("read")) == 1
                    val type = it.getInt(it.getColumnIndexOrThrow("type"))
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    
                    val message = Message(
                        id = id,
                        threadId = threadId,
                        address = address,
                        body = body,
                        date = date,
                        isRead = isRead,
                        isOutgoing = type == Message.TYPE_SENT
                    )
                    
                    if (!threadMessages.containsKey(threadId)) {
                        threadMessages[threadId] = mutableListOf()
                    }
                    threadMessages[threadId]?.add(message)
                }
                
                // Create conversations from grouped messages
                threadMessages.forEach { (threadId, messages) ->
                    if (messages.isNotEmpty()) {
                        val lastMessage = messages.first() // Already sorted by date DESC
                        val address = lastMessage.address
                        val unreadCount = messages.count { !it.isRead && !it.isOutgoing }
                        
                        conversations[threadId] = Conversation(
                            threadId = threadId,
                            address = address,
                            contactName = getContactName(address),
                            messageCount = messages.size,
                            unreadCount = unreadCount,
                            lastMessageTime = lastMessage.date,
                            lastMessageText = lastMessage.body,
                            lastMessageIsOutgoing = lastMessage.isOutgoing
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading conversations", e)
        }
        
        conversations.values.sortedByDescending { it.lastMessageTime }
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