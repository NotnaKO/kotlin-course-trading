package org.example

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

const val DERIBIT_TEST_WS_URL = "wss://test.deribit.com/ws/api/v2"
const val INSTRUMENT_NAME_DEFAULT = "BTC-PERPETUAL"
const val TICKER_SUBSCRIPTION_INTERVAL = "100ms"
const val SLIDING_WINDOW_SECONDS = 10L
const val TREND_OUTPUT_INTERVAL_MS = 1000L
const val MIN_POINTS_FOR_TREND = 5

val requestId = AtomicInteger(1)
val priceDataWindow = ArrayDeque<PricePoint>()
val priceDataWindowMutex = Mutex()

class TrendData(
    val trend: LinearTrend,
    val currentPrice: Double,
)

fun main(): Unit = runBlocking {
    print("Enter instrument name (e.g., BTC-PERPETUAL, ETH-PERPETUAL) or press Enter for default ($INSTRUMENT_NAME_DEFAULT):")
    val instrumentName = readlnOrNull()?.takeIf { it.isNotBlank() } ?: INSTRUMENT_NAME_DEFAULT
    println("Using instrument: $instrumentName")

    val client = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(json)
        }
    }

    launch {
        var lastOutputTime = 0L
        while (isActive) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastOutputTime >= TREND_OUTPUT_INTERVAL_MS) {
                val data = priceDataWindowMutex.withLock {
                    if (priceDataWindow.size >= MIN_POINTS_FOR_TREND) {
                        val currentPoints = priceDataWindow.toList()
                        val trend = calculateLinearTrend(currentPoints)
                        TrendData(trend, currentPoints.last().price)
                    } else {
                        null
                    }
                }

                if (data != null) {
                    val trendDirection = when {
                        data.trend.slope > 0.00001 -> "UP"
                        data.trend.slope < -0.00001 -> "DOWN"
                        else -> "FLAT"
                    }
                    val firstTimestamp = priceDataWindowMutex.withLock { priceDataWindow.first().timestamp }
                    val relativeTimeNow =
                        (System.currentTimeMillis() - firstTimestamp).toDouble() / 1000.0 // in seconds
                    val extrapolatedPrice = data.trend.slope * (relativeTimeNow + 1.0) + data.trend.intercept

                    println(
                        "Inst: $instrumentName | Window: ${SLIDING_WINDOW_SECONDS}s (${priceDataWindowMutex.withLock { priceDataWindow.size }} pts) | " +
                                "Price: ${"%.2f".format(data.currentPrice)} | Trend: $trendDirection (${
                                    "%.4f".format(data.trend.slope)
                                } price/sec) | " +
                                "Extrapolated next (1s): ${"%.2f".format(extrapolatedPrice)}"
                    )
                    lastOutputTime = currentTime
                } else {
                    println(
                        "Inst: $instrumentName | Window: ${SLIDING_WINDOW_SECONDS}s (${priceDataWindowMutex.withLock { priceDataWindow.size }} pts) | (collecting data...)"
                    )
                    lastOutputTime = currentTime
                }
            }
            delay(100)
        }
    }

    launch {
        try {
            client.webSocket(DERIBIT_TEST_WS_URL) {
                println("Connected to Deribit WebSocket.")

                val subscribeRequest = DeribitRequest(
                    id = requestId.getAndIncrement(),
                    method = "public/subscribe",
                    params = SubscribeParams(channels = listOf("ticker.$instrumentName.$TICKER_SUBSCRIPTION_INTERVAL"))
                )
                send(Frame.Text(json.encodeToString(subscribeRequest)))
                println("Sent subscription request: ${json.encodeToString(subscribeRequest)}")


                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        try {
                            val response = json.decodeFromString<DeribitResponse<TickerData>>(text)
                            when {
                                response.method == "subscription" && response.params?.channel == "ticker.$instrumentName.$TICKER_SUBSCRIPTION_INTERVAL" -> {
                                    val tickerData = response.params.data
                                    val pricePoint =
                                        PricePoint(tickerData.timestamp, tickerData.markPrice)
                                    addPricePointToWindow(pricePoint)
                                }

                                response.result != null -> println("Subscription confirmed for channels: ${response.result}")
                                response.error != null -> System.err.println("Error from Deribit: ${response.error.message} (Code: ${response.error.code})")
                            }
                        } catch (e: Exception) {
                            System.err.println("Error parsing WebSocket message: $text\n${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("WebSocket connection error: ${e.message}")
            e.printStackTrace()
        } finally {
            client.close()
            println("WebSocket client closed.")
        }
    }

}

suspend fun addPricePointToWindow(pricePoint: PricePoint) {
    priceDataWindowMutex.withLock {
        priceDataWindow.addLast(pricePoint)
        // Remove old data points
        val windowLimitTimestamp = pricePoint.timestamp - (SLIDING_WINDOW_SECONDS * 1000)
        while (priceDataWindow.isNotEmpty() && priceDataWindow.first().timestamp < windowLimitTimestamp) {
            priceDataWindow.removeFirst()
        }
    }
}

class LinearTrend(val slope: Double, val intercept: Double)

fun calculateLinearTrend(points: List<PricePoint>): LinearTrend {
    if (points.size < 2) return LinearTrend(0.0, points.firstOrNull()?.price ?: 0.0)

    val n = points.size.toDouble()
    val firstTimestamp = points.first().timestamp

    val x = points.map { (it.timestamp - firstTimestamp).toDouble() / 1000.0 } // Relative time in seconds
    val y = points.map { it.price }

    val sumX = x.sum()
    val sumY = y.sum()
    val sumXY = x.zip(y) { x, y -> x * y }.sum()
    val sumXSquare = x.sumOf { it * it }

    val denominator = (n * sumXSquare - sumX * sumX)
    if (denominator == 0.0) {
        val averagePrice = y.average()
        return LinearTrend(0.0, averagePrice)
    }
    val slope = (n * sumXY - sumX * sumY) / denominator
    val intercept = (sumY - slope * sumX) / n

    return LinearTrend(slope, intercept)
}
