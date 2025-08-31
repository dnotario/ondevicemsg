package com.dnotario.ondevicemsg.data.repository

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.dnotario.ondevicemsg.utils.FuzzyMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "ContactsRepository"
    }
    
    /**
     * Get all contacts with phone numbers (deduplicated)
     */
    suspend fun getAllContacts(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<Pair<String, String>>()
        val seenEntries = mutableSetOf<String>() // Track unique name+number combinations
        val contactNumberCount = mutableMapOf<String, Int>() // Track how many numbers each contact has
        
        try {
            // First pass: count unique numbers per contact
            val countCursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )
            
            countCursor?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val uniqueNumbersPerContact = mutableMapOf<String, MutableSet<String>>()
                
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    val number = cursor.getString(numberIndex)
                    if (!name.isNullOrEmpty() && !number.isNullOrEmpty()) {
                        val normalizedNumber = number.replace(Regex("[^+0-9]"), "")
                        uniqueNumbersPerContact.getOrPut(name) { mutableSetOf() }.add(normalizedNumber)
                    }
                }
                
                uniqueNumbersPerContact.forEach { (name, numbers) ->
                    contactNumberCount[name] = numbers.size
                }
            }
            
            // Second pass: get contacts with deduplication
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            
            cursor?.use {
                val nameIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex)
                    val number = it.getString(numberIndex)
                    val type = it.getInt(typeIndex)
                    val label = it.getString(labelIndex)
                    
                    if (!name.isNullOrEmpty() && !number.isNullOrEmpty()) {
                        // Normalize phone number (remove formatting)
                        val normalizedNumber = number.replace(Regex("[^+0-9]"), "")
                        
                        // Create unique key for deduplication
                        val uniqueKey = "$name|$normalizedNumber"
                        
                        // Skip if we've already seen this exact name+number combination
                        if (seenEntries.contains(uniqueKey)) {
                            continue
                        }
                        seenEntries.add(uniqueKey)
                        
                        // Only add type label if contact has multiple different numbers
                        val hasMultipleNumbers = (contactNumberCount[name] ?: 1) > 1
                        
                        val displayName = if (hasMultipleNumbers) {
                            // Get phone type label
                            val typeLabel = when (type) {
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                                ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
                                ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "Work Fax"
                                ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "Home Fax"
                                ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "Pager"
                                ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Other"
                                ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> label ?: "Custom"
                                else -> ""
                            }
                            
                            if (typeLabel.isNotEmpty()) {
                                "$name ($typeLabel)"
                            } else {
                                name
                            }
                        } else {
                            name
                        }
                        
                        contacts.add(displayName to normalizedNumber)
                    }
                }
            }
            
            // Log deduplication info
            val contactsWithMultipleNumbers = contactNumberCount.filter { it.value > 1 }
            if (contactsWithMultipleNumbers.isNotEmpty()) {
                Log.d(TAG, "Found ${contactsWithMultipleNumbers.size} contacts with multiple numbers")
            }
            
            Log.d(TAG, "Loaded ${contacts.size} unique contact entries from ${contactNumberCount.size} contacts")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts", e)
        }
        
        return@withContext contacts
    }
    
    /**
     * Search contacts using fuzzy matching (returns grouped contacts)
     */
    suspend fun searchContacts(
        query: String,
        threshold: Float = 0.3f,
        maxResults: Int = 3
    ): List<FuzzyMatcher.MatchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext emptyList()
        }
        
        // Get all contacts with labels
        val allContacts = getAllContactsGrouped()
        
        // Try both original query and query without spaces (for spelled names like "J O N")
        val queryWithoutSpaces = query.replace(" ", "")
        val useAlternateQuery = query.contains(" ") && queryWithoutSpaces.length >= 2
        
        if (useAlternateQuery) {
            Log.d(TAG, "Searching with both '$query' and '$queryWithoutSpaces'")
        }
        
        // Score contacts with both queries
        val scoredContactsMap = mutableMapOf<String, FuzzyMatcher.MatchResult>()
        
        allContacts.forEach { (name, phoneNumbers) ->
            // Score with original query
            val scoreOriginal = FuzzyMatcher.fuzzyScore(query, name)
            
            // Score with no-spaces query if applicable
            val scoreNoSpaces = if (useAlternateQuery) {
                FuzzyMatcher.fuzzyScore(queryWithoutSpaces, name)
            } else {
                0f
            }
            
            // Use the better score, with slight preference for original query
            val bestScore = if (scoreNoSpaces > scoreOriginal * 1.05f) {
                scoreNoSpaces
            } else {
                scoreOriginal
            }
            
            // Store the best result for this contact
            if (bestScore >= threshold) {
                scoredContactsMap[name] = FuzzyMatcher.MatchResult(name, phoneNumbers, bestScore)
            }
        }
        
        // Sort by score and return top results
        return@withContext scoredContactsMap.values
            .sortedByDescending { it.score }
            .take(maxResults)
    }
    
    /**
     * Get all contacts grouped by name with their phone numbers
     */
    private suspend fun getAllContactsGrouped(): Map<String, List<FuzzyMatcher.PhoneNumber>> = withContext(Dispatchers.IO) {
        val contactsMap = mutableMapOf<String, MutableList<FuzzyMatcher.PhoneNumber>>()
        
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            
            cursor?.use {
                val nameIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                
                val seenNumbers = mutableSetOf<String>() // Track unique numbers per contact
                
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex)
                    val number = it.getString(numberIndex)
                    val type = it.getInt(typeIndex)
                    val label = it.getString(labelIndex)
                    
                    if (!name.isNullOrEmpty() && !number.isNullOrEmpty()) {
                        val normalizedNumber = number.replace(Regex("[^+0-9]"), "")
                        
                        // Create unique key for this contact+number
                        val uniqueKey = "$name|$normalizedNumber"
                        
                        // Skip if we've seen this exact combination
                        if (seenNumbers.contains(uniqueKey)) {
                            continue
                        }
                        seenNumbers.add(uniqueKey)
                        
                        // Get phone type label
                        val typeLabel = when (type) {
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
                            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "Work Fax"
                            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "Home Fax"
                            ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "Pager"
                            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Other"
                            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> label ?: "Custom"
                            else -> ""
                        }
                        
                        // Add to map
                        if (!contactsMap.containsKey(name)) {
                            contactsMap[name] = mutableListOf()
                        }
                        contactsMap[name]?.add(FuzzyMatcher.PhoneNumber(normalizedNumber, typeLabel))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading grouped contacts", e)
        }
        
        return@withContext contactsMap
    }
    
    /**
     * Get contact name for a phone number
     */
    suspend fun getContactName(phoneNumber: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?"
            val selectionArgs = arrayOf(phoneNumber)
            
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                selection,
                selectionArgs,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    return@withContext it.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact name for $phoneNumber", e)
        }
        
        return@withContext null
    }
}