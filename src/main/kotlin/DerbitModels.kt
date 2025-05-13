package org.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeribitRequest<T>(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: T
)

@Serializable
data class SubscribeParams(
    val channels: List<String>
)

@Serializable
data class DeribitResponse<T>(
    val jsonrpc: String,
    val id: Int? = null,
    val method: String? = null,
    val params: DeribitNotificationParams<T>? = null,
    val result: List<String>? = null,
    val error: DeribitError? = null,
)

@Serializable
data class DeribitError(
    val code: Int,
    val message: String,
    val data: ErrorData? = null
) {
    @Serializable
    data class ErrorData(
        val reason: String? = null,
        val param: String? = null
    )
}


@Serializable
data class DeribitNotificationParams<T>(
    val channel: String,
    val data: T
)

// Specific Ticker Data
@Serializable
data class TickerData(
    @SerialName("mark_price") val markPrice: Double,
    val timestamp: Long, // Milliseconds since UNIX epoch
)


data class PricePoint(val timestamp: Long, val price: Double)