package com.dnotario.ondevicemsg.data.models

data class Conversation(
    val threadId: Long,
    val address: String,
    val contactName: String?,
    val messageCount: Int,
    val unreadCount: Int,
    val lastMessageTime: Long,
    val lastMessageText: String?,
    val lastMessageIsOutgoing: Boolean = false
) {
    // Custom equals to only compare fields that matter for UI updates
    fun hasContentChanges(other: Conversation?): Boolean {
        if (other == null) return true
        return lastMessageTime != other.lastMessageTime ||
               lastMessageText != other.lastMessageText ||
               unreadCount != other.unreadCount ||
               lastMessageIsOutgoing != other.lastMessageIsOutgoing
    }
    
    fun getDisplayName(): String {
        return contactName ?: formatPhoneNumber(address)
    }
    
    private fun formatPhoneNumber(number: String): String {
        val cleaned = number.filter { it.isDigit() }
        return when {
            cleaned.length == 10 -> "(${cleaned.substring(0, 3)}) ${cleaned.substring(3, 6)}-${cleaned.substring(6)}"
            cleaned.length == 11 && cleaned.startsWith("1") -> 
                "(${cleaned.substring(1, 4)}) ${cleaned.substring(4, 7)}-${cleaned.substring(7)}"
            else -> number
        }
    }
}