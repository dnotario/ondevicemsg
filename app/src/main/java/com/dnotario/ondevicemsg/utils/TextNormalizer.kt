package com.dnotario.ondevicemsg.utils

import java.util.regex.Pattern

/**
 * Normalizes text for better Text-to-Speech output
 */
object TextNormalizer {
    
    // Regex pattern to match URLs
    private val URL_PATTERN = Pattern.compile(
        "(https?://)?([\\w.-]+\\.)+[\\w]{2,}(/[^\\s]*)?"
    )
    
    /**
     * Normalizes text for TTS, handling URLs and other patterns
     */
    fun normalizeForTTS(text: String): String {
        var normalized = text
        
        // Normalize URLs
        normalized = normalizeUrls(normalized)
        
        return normalized
    }
    
    /**
     * Normalizes URLs to be spoken naturally
     * Examples:
     * - www.google.com -> "google dot com"
     * - google.com -> "google dot com"
     * - https://developers.google.com/ml-kit/path -> "google dot com URL"
     */
    private fun normalizeUrls(text: String): String {
        val matcher = URL_PATTERN.matcher(text)
        val result = StringBuffer()
        
        while (matcher.find()) {
            val fullUrl = matcher.group()
            val spokenUrl = convertUrlToSpoken(fullUrl)
            matcher.appendReplacement(result, spokenUrl)
        }
        matcher.appendTail(result)
        
        return result.toString()
    }
    
    /**
     * Converts a URL to spoken format
     * Simple URLs: spoken fully (e.g., "google dot com")
     * Complex URLs: domain + "URL" (e.g., "google dot com URL")
     */
    private fun convertUrlToSpoken(url: String): String {
        val hasProtocol = url.startsWith("http://") || url.startsWith("https://")
        val hasPath = url.contains("/") && url.indexOf("/") < url.length - 1
        val hasSubdomain = url.count { it == '.' } > 1
        
        // Clean the URL to get the domain
        var cleanUrl = url
        cleanUrl = cleanUrl.replace("https://", "")
        cleanUrl = cleanUrl.replace("http://", "")
        
        // Remove www prefix
        if (cleanUrl.startsWith("www.")) {
            cleanUrl = cleanUrl.substring(4)
        }
        
        // Extract just the domain part (before any path)
        val pathIndex = cleanUrl.indexOf('/')
        val domain = if (pathIndex != -1) {
            cleanUrl.substring(0, pathIndex)
        } else {
            cleanUrl
        }
        
        // Check if it's complex (has path, subdomain other than www, or query params)
        val isComplex = hasPath || 
                       (hasSubdomain && !url.contains("www.")) || 
                       url.contains("?") || 
                       url.contains("#")
        
        // Extract the main domain (last two parts for most cases)
        val domainParts = domain.split(".")
        val mainDomain = if (domainParts.size >= 2) {
            // Get the last two parts (e.g., "google.com" from "developers.google.com")
            "${domainParts[domainParts.size - 2]}.${domainParts[domainParts.size - 1]}"
        } else {
            domain
        }
        
        // Convert to spoken format
        val spokenDomain = convertDomainToSpoken(mainDomain)
        
        return if (isComplex) {
            "$spokenDomain URL"
        } else {
            spokenDomain
        }
    }
    
    /**
     * Converts a domain to spoken format
     * Example: google.com -> "google dot com"
     */
    private fun convertDomainToSpoken(domain: String): String {
        // Handle special cases for common domains
        val specialCases = mapOf(
            "gmail.com" to "gmail",
            "yahoo.com" to "yahoo",
            "outlook.com" to "outlook"
        )
        
        specialCases[domain]?.let { return it }
        
        // Replace dots with " dot "
        return domain.replace(".", " dot ")
    }
}