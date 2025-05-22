package com.tobylu.bitcoin_trading_view.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class KlineData(
    val openTime: Long,
    val closeTime: Long,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Float
)

class BTCWebSocketManager {
    private val client = OkHttpClient()
    private var priceWebSocket: WebSocket? = null
    private var klineWebSocket: WebSocket? = null

    // 價格數據流
    private val _btcPrice = MutableStateFlow("Connecting...")
    val btcPrice: StateFlow<String> get() = _btcPrice

    // K線數據流
    private val _klineData = MutableStateFlow<List<KlineData>>(emptyList())
    val klineData: StateFlow<List<KlineData>> get() = _klineData

    private val klineHistory = mutableListOf<KlineData>()

    fun connect() {
        connectPriceStream()
        connectKlineStream()
    }

    private fun connectPriceStream() {
        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/ws/btcusdt@trade")
            .build()

        priceWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updatePrice("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val price = Regex("\"p\":\"(.*?)\"").find(text)?.groupValues?.get(1)
                    price?.let { updatePrice("$it USD") }
                } catch (e: Exception) {
                    // 防止解析錯誤導致崩潰
                    println("價格解析錯誤: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updatePrice("Connection failed: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updatePrice("Connection closed")
            }
        })
    }

    private fun connectKlineStream() {
        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/ws/btcusdt@kline_1m")
            .build()

        klineWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("K線連接成功")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 在背景線程處理數據解析
                CoroutineScope(Dispatchers.IO).launch {
                    parseKlineData(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("K線連接失敗: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("K線連接關閉")
            }
        })
    }

    private fun parseKlineData(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val klineJson = json.getJSONObject("k")

            val klineData = KlineData(
                openTime = klineJson.getLong("t"),
                closeTime = klineJson.getLong("T"),
                open = klineJson.getString("o").toFloat(),
                high = klineJson.getString("h").toFloat(),
                low = klineJson.getString("l").toFloat(),
                close = klineJson.getString("c").toFloat(),
                volume = klineJson.getString("v").toFloat()
            )

            // 更新或添加 K 線數據
            updateKlineData(klineData)

        } catch (e: Exception) {
            println("解析 K 線數據錯誤: ${e.message}")
        }
    }

    private fun updateKlineData(newKline: KlineData) {
        // 在主線程更新 UI 相關數據
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 防止數據過多導致內存問題
                if (klineHistory.size >= 100) {
                    klineHistory.removeAt(0)
                }

                // 檢查是否是更新現有K線還是新增
                val existingIndex = klineHistory.indexOfLast {
                    it.openTime == newKline.openTime
                }

                if (existingIndex >= 0) {
                    // 更新現有的 K 線
                    klineHistory[existingIndex] = newKline
                } else {
                    // 添加新的 K 線
                    klineHistory.add(newKline)
                }

                // 創建新的列表來觸發 StateFlow 更新
                _klineData.value = ArrayList(klineHistory)

            } catch (e: Exception) {
                println("更新K線數據錯誤: ${e.message}")
            }
        }
    }

    fun disconnect() {
        try {
            priceWebSocket?.close(1000, null)
            klineWebSocket?.close(1000, null)
        } catch (e: Exception) {
            println("斷開連接錯誤: ${e.message}")
        }
    }

    private fun updatePrice(price: String) {
        CoroutineScope(Dispatchers.Main).launch {
            _btcPrice.value = price
        }
    }
}