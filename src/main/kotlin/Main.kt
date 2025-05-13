package org.example

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
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

class TrendData(
    val slope: Double,
    val intercept: Double,
    val currentPrice: Double,
)

fun main() = runBlocking {
    println("Enter instrument name (e.g., BTC-PERPETUAL, ETH-PERPETUAL) or press Enter for default ($INSTRUMENT_NAME_DEFAULT):")
    val instrumentName = readlnOrNull()?.takeIf { it.isNotBlank() } ?: INSTRUMENT_NAME_DEFAULT
    println("Using instrument: $instrumentName")

    val client = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(json)
        }
    }

    val trendJob = launch {
        var lastOutputTime = 0L
        while (isActive) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastOutputTime >= TREND_OUTPUT_INTERVAL_MS) {
                val data = synchronized(priceDataWindow) {
                    if (priceDataWindow.size >= MIN_POINTS_FOR_TREND) {
                        val currentPoints = priceDataWindow.toList()
                        val (s, i) = calculateLinearTrend(currentPoints)
                        TrendData(s, i, currentPoints.last().price)
                    } else {
                        null
                    }
                }

                if (data != null) {
                    val trendDirection = when {
                        data.slope > 0.00001 -> "UP"
                        data.slope < -0.00001 -> "DOWN"
                        else -> "FLAT"
                    }
                    val firstTimestamp = synchronized(priceDataWindow) { priceDataWindow.first().timestamp }
                    val relativeTimeNow =
                        (System.currentTimeMillis() - firstTimestamp).toDouble() / 1000.0 // in seconds
                    val extrapolatedPrice = data.slope * (relativeTimeNow + 1.0) + data.intercept

                    println(
                        "Inst: $instrumentName | Window: ${SLIDING_WINDOW_SECONDS}s (${priceDataWindow.size} pts) | " +
                                "Price: ${"%.2f".format(data.currentPrice)} | Trend: $trendDirection (${
                                    "%.4f".format(data.slope)
                                } price/sec) | " +
                                "Extrapolated next (1s): ${"%.2f".format(extrapolatedPrice)}"
                    )
                    lastOutputTime = currentTime
                } else {
                    println(
                        "Instrument: $instrumentName | Window: ${SLIDING_WINDOW_SECONDS}s (${priceDataWindow.size} pts) | (collecting data...)"
                    )
                    lastOutputTime = currentTime
                }
            }
            delay(100)
        }
    }

    val wsJob = launch {
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

                            if (response.method == "subscription" && response.params?.channel == "ticker.$instrumentName.$TICKER_SUBSCRIPTION_INTERVAL") {
                                val tickerData = response.params.data
                                val pricePoint =
                                    PricePoint(tickerData.timestamp, tickerData.markPrice)
                                addPricePointToWindow(pricePoint)
                            } else if (response.id != null && response.result != null) {
                                println("Subscription confirmed for channels: ${response.result}")
                            } else if (response.id != null && response.error != null) {
                                System.err.println("Error from Deribit: ${response.error.message} (Code: ${response.error.code})")
                            }
                        } catch (e: Exception) {
                            System.err.println("Error parsing WebSocket message: $text \n ${e.message}")
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

    joinAll(wsJob, trendJob)
}

fun addPricePointToWindow(pricePoint: PricePoint) {
    synchronized(priceDataWindow) {
        priceDataWindow.addLast(pricePoint)
        // Remove old data points
        val windowLimitTimestamp = pricePoint.timestamp - (SLIDING_WINDOW_SECONDS * 1000)
        while (priceDataWindow.isNotEmpty() && priceDataWindow.first().timestamp < windowLimitTimestamp) {
            priceDataWindow.removeFirst()
        }
    }
}


fun calculateLinearTrend(points: List<PricePoint>): Pair<Double, Double> {
    if (points.size < 2) return Pair(0.0, points.firstOrNull()?.price ?: 0.0)

    val n = points.size.toDouble()
    val firstTimestamp = points.first().timestamp

    val x = points.map { (it.timestamp - firstTimestamp).toDouble() / 1000.0 } // Relative time in seconds
    val y = points.map { it.price }

    val sumX = x.sum()
    val sumY = y.sum()
    val sumXY = x.zip(y).sumOf { it.first * it.second }
    val sumXSquare = x.sumOf { it * it }

    val denominator = (n * sumXSquare - sumX * sumX)
    val slope = (n * sumXY - sumX * sumY) / denominator
    val intercept = (sumY - slope * sumX) / n

    return Pair(slope, intercept)
}
