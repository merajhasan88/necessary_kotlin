package com.necessarysdk

/**
 * The text [field] of an [answer] your backend gave. A field it left out is
 * an [ApiException], as every failure of a call is, never a cast error.
 */
internal fun answerText(answer: Map<String, Any?>, field: String): String {
    val value = answer[field]
    if (value is String && value.isNotEmpty()) return value
    throw ApiException("The backend answered no $field.", code = "invalid_backend_answer")
}
