package il.kavmonitor.shared.model

/** Display-only table of common operator refs (Stride `operator_ref` == curlbus `operator_id`). */
object Operators {
    private val names = mapOf(
        3 to "אגד", 4 to "אגד תעבורה", 5 to "דן", 14 to "נתיב אקספרס", 15 to "מטרופולין",
        16 to "סופרבוס", 18 to "קווים", 25 to "אפיקים", 31 to "דן בדרום", 32 to "דן באר שבע", 37 to "אקסטרה",
    )
    fun displayName(operatorRef: Int, fallback: String? = null): String =
        fallback?.takeIf { it.isNotBlank() } ?: names[operatorRef] ?: operatorRef.toString()
}
