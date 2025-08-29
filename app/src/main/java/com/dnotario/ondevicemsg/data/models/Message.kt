package com.dnotario.ondevicemsg.data.models

data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val isRead: Boolean,
    val isOutgoing: Boolean,
    val isMms: Boolean = false,
    val hasImage: Boolean = false,
    val imageUri: String? = null
) {
    companion object {
        const val TYPE_INBOX = 1
        const val TYPE_SENT = 2
    }
}