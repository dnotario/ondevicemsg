package com.dnotario.ondevicemsg.utils

import kotlin.math.max
import kotlin.math.min

object FuzzyMatcher {
    
    data class PhoneNumber(
        val number: String,
        val type: String = ""
    )
    
    data class MatchResult(
        val name: String,
        val phoneNumbers: List<PhoneNumber>,
        val score: Float
    ) {
        // Backward compatibility constructor
        constructor(name: String, phoneNumber: String, score: Float) : 
            this(name, listOf(PhoneNumber(phoneNumber)), score)
        
        // Get primary phone number for backward compatibility
        val phoneNumber: String get() = phoneNumbers.firstOrNull()?.number ?: ""
    }
    
    /**
     * Calculate Levenshtein distance between two strings
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j
        
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1].lowercaseChar() == s2[j - 1].lowercaseChar()) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,     // deletion
                    dp[i][j - 1] + 1,     // insertion
                    dp[i - 1][j - 1] + cost  // substitution
                )
            }
        }
        
        return dp[len1][len2]
    }
    
    /**
     * Convert name to Soundex phonetic code
     */
    fun soundex(name: String): String {
        if (name.isEmpty()) return ""
        
        val upperName = name.uppercase()
        val firstChar = upperName[0]
        
        val coded = upperName.drop(1)
            .map { char ->
                when (char) {
                    'B', 'F', 'P', 'V' -> '1'
                    'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2'
                    'D', 'T' -> '3'
                    'L' -> '4'
                    'M', 'N' -> '5'
                    'R' -> '6'
                    else -> '0'
                }
            }
            .filter { it != '0' }
            .joinToString("")
        
        // Remove consecutive duplicates
        val cleaned = StringBuilder()
        var lastChar = ' '
        for (c in coded) {
            if (c != lastChar) {
                cleaned.append(c)
                lastChar = c
            }
        }
        
        return (firstChar + cleaned.toString()).take(4).padEnd(4, '0')
    }
    
    /**
     * Calculate fuzzy match score between input and contact name
     */
    fun fuzzyScore(input: String, contactName: String): Float {
        val inputLower = input.lowercase().trim()
        // Remove phone type label (e.g., "(Mobile)") for matching
        val nameWithoutType = contactName.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        val nameLower = nameWithoutType.lowercase()
        
        // Exact match
        if (inputLower == nameLower) return 1.0f
        
        // Starts with input (very strong match)
        if (nameLower.startsWith(inputLower)) return 0.95f
        
        // Check each word in the contact name
        val nameWords = nameLower.split(" ", "-", ".")
        val inputWords = inputLower.split(" ", "-", ".")
        
        // First check if the FIRST word (usually first name) starts with input
        if (nameWords.isNotEmpty() && nameWords[0].startsWith(inputLower)) {
            return 0.92f  // Higher score for first name match
        }
        
        // Check if input matches beginning of any word
        for ((index, nameWord) in nameWords.withIndex()) {
            if (nameWord.startsWith(inputLower)) {
                // Give higher score to earlier words (first name > last name)
                return if (index == 0) 0.9f else 0.85f
            }
        }
        
        // Check for word-by-word matching with variations
        for ((inputIndex, inputWord) in inputWords.withIndex()) {
            for ((nameIndex, nameWord) in nameWords.withIndex()) {
                // Check exact starts with
                if (nameWord.startsWith(inputWord) || inputWord.startsWith(nameWord)) {
                    // Prioritize first name matches
                    return if (nameIndex == 0 && inputIndex == 0) 0.88f else 0.82f
                }
                
                // Check for name variations (John/Jon)
                if (areNameVariation(inputWord, nameWord)) {
                    // Higher score if it's the first name
                    return if (nameIndex == 0 && inputIndex == 0) 0.86f else 0.80f
                }
                
                // Check for very close matches (1-2 char difference)
                if (inputWord.length >= 3 && nameWord.length >= 3) {
                    val distance = levenshteinDistance(inputWord, nameWord)
                    if (distance <= 1) {
                        return if (nameIndex == 0 && inputIndex == 0) 0.84f else 0.78f
                    }
                }
            }
        }
        
        // Check if all input words are found in name words (with fuzzy matching)
        val matchedWords = inputWords.count { inputWord ->
            nameWords.any { nameWord ->
                // Check for exact starts with
                nameWord.startsWith(inputWord) ||
                // Check for very close matches (1-2 char difference for short names)
                (inputWord.length >= 3 && levenshteinDistance(inputWord, nameWord) <= 2)
            }
        }
        if (matchedWords == inputWords.size && inputWords.isNotEmpty()) {
            return 0.8f
        }
        
        // Contains input anywhere
        if (nameLower.contains(inputLower)) return 0.7f
        
        // Levenshtein distance for typos
        val distance = levenshteinDistance(inputLower, nameLower)
        val maxLen = max(inputLower.length, nameLower.length)
        val levenshteinScore = 1.0f - (distance.toFloat() / maxLen)
        
        // Good Levenshtein match (allowing for typos)
        if (levenshteinScore > 0.75f) {
            return levenshteinScore * 0.9f  // Scale down slightly
        }
        
        // Phonetic matching using Soundex
        if (soundex(inputLower) == soundex(nameLower)) {
            return 0.65f
        }
        
        // Check phonetic match on individual words
        for (inputWord in inputWords) {
            for (nameWord in nameWords) {
                if (soundex(inputWord) == soundex(nameWord)) {
                    return 0.6f
                }
            }
        }
        
        // Check substring matches with length consideration
        for (nameWord in nameWords) {
            if (inputLower.length >= 3 && nameWord.contains(inputLower)) {
                return 0.5f
            }
        }
        
        // No good match
        return 0.0f
    }
    
    /**
     * Find best matching contacts for input
     */
    fun findMatches(
        input: String,
        contacts: List<Pair<String, String>>, // List of (name, phoneNumber) pairs
        threshold: Float = 0.4f,
        maxResults: Int = 3
    ): List<MatchResult> {
        if (input.isBlank()) return emptyList()
        
        return contacts
            .map { (name, phone) ->
                MatchResult(name, phone, fuzzyScore(input, name))
            }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(maxResults)
    }
    
    /**
     * Check if two words are name variations of each other
     */
    private fun areNameVariation(word1: String, word2: String): Boolean {
        val nameVariations = mapOf(
            "john" to setOf("jon", "johnny"),
            "jon" to setOf("john", "jonathan"),
            "jonathan" to setOf("jon", "john"),
            "robert" to setOf("rob", "bob", "bobby"),
            "rob" to setOf("robert", "bob"),
            "bob" to setOf("robert", "rob"),
            "william" to setOf("will", "bill", "billy"),
            "will" to setOf("william", "bill"),
            "bill" to setOf("william", "will"),
            "richard" to setOf("rick", "dick", "rich"),
            "rick" to setOf("richard", "rich"),
            "michael" to setOf("mike", "mick"),
            "mike" to setOf("michael"),
            "james" to setOf("jim", "jimmy"),
            "jim" to setOf("james", "jimmy"),
            "joseph" to setOf("joe", "joey"),
            "joe" to setOf("joseph", "joey"),
            "thomas" to setOf("tom", "tommy"),
            "tom" to setOf("thomas", "tommy"),
            "charles" to setOf("charlie", "chuck"),
            "charlie" to setOf("charles", "chuck"),
            "christopher" to setOf("chris"),
            "chris" to setOf("christopher"),
            "daniel" to setOf("dan", "danny"),
            "dan" to setOf("daniel", "danny"),
            "matthew" to setOf("matt"),
            "matt" to setOf("matthew"),
            "anthony" to setOf("tony"),
            "tony" to setOf("anthony"),
            "nicholas" to setOf("nick"),
            "nick" to setOf("nicholas"),
            "elizabeth" to setOf("liz", "beth", "betty"),
            "liz" to setOf("elizabeth"),
            "beth" to setOf("elizabeth"),
            "jennifer" to setOf("jen", "jenny"),
            "jen" to setOf("jennifer", "jenny"),
            "katherine" to setOf("kate", "kathy", "kat"),
            "kate" to setOf("katherine", "katie"),
            "katie" to setOf("katherine", "kate"),
            "rebecca" to setOf("becca", "becky"),
            "becca" to setOf("rebecca"),
            "becky" to setOf("rebecca")
        )
        
        val w1Lower = word1.lowercase()
        val w2Lower = word2.lowercase()
        
        // Check direct variation
        val variations = nameVariations[w1Lower]
        if (variations != null && w2Lower in variations) {
            return true
        }
        
        // Check reverse
        val reverseVariations = nameVariations[w2Lower]
        return reverseVariations != null && w1Lower in reverseVariations
    }
    
    /**
     * Check if words are common name variations (for backward compatibility)
     */
    private fun areNameVariations(inputWords: List<String>, nameWords: List<String>): Boolean {
        for (inputWord in inputWords) {
            for (nameWord in nameWords) {
                if (areNameVariation(inputWord, nameWord)) {
                    return true
                }
            }
        }
        return false
    }
}