package com.tobylu.bitcoin_trading_view.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var priceWebSocket: WebSocket? = null
    private var klineWebSocket: WebSocket? = null

    // 價格數據流
    private val _btcPrice = MutableStateFlow("Connecting...")
    val btcPrice: StateFlow<String> get() = _btcPrice

    // K線數據流
    private val _klineData = MutableStateFlow<List<KlineData>>(emptyList())
    val klineData: StateFlow<List<KlineData>> get() = _klineData

    // 載入狀態
    private val _loadingStatus = MutableStateFlow("正在初始化...")
    val loadingStatus: StateFlow<String> get() = _loadingStatus

    private val klineHistory = mutableListOf<KlineData>()

    fun connect() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateLoadingStatus("正在載入歷史數據...")

                // 1. 先載入過去10分鐘的歷史數據
                loadHistoricalData()

                // 2. 然後連接 WebSocket 接續即時數據
                connectPriceStream()
                connectKlineStream()

                updateLoadingStatus("連接完成")

            } catch (e: Exception) {
                updateLoadingStatus("載入失敗: ${e.message}")
            }
        }
    }

    private suspend fun loadHistoricalData() {
        try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (30 * 60 * 1000) // 改成30分鐘前，獲得更多數據

            // Binance REST API for Klines
            val url = "https://api.binance.com/api/v3/klines?" +
                    "symbol=BTCUSDT&" +
                    "interval=1m&" +
                    "startTime=$startTime&" +
                    "endTime=$endTime&" +
                    "limit=30"  // 30根K線

            updateLoadingStatus("正在下載 K 線數據...")

            val request = Request.Builder()
                .url(url)
                .build()

            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        responseBody?.let { parseHistoricalData(it) }
                    } else {
                        throw Exception("API 請求失敗: ${response.code}")
                    }
                }
            }

        } catch (e: Exception) {
            println("載入歷史數據錯誤: ${e.message}")
            updateLoadingStatus("歷史數據載入失敗")
        }
    }

    private suspend fun parseHistoricalData(jsonString: String) {
        try {
            val jsonArray = JSONArray(jsonString)
            val historicalKlines = mutableListOf<KlineData>()

            for (i in 0 until jsonArray.length()) {
                val klineArray = jsonArray.getJSONArray(i)

                val klineData = KlineData(
                    openTime = klineArray.getLong(0),
                    closeTime = klineArray.getLong(6),
                    open = klineArray.getString(1).toFloat(),
                    high = klineArray.getString(2).toFloat(),
                    low = klineArray.getString(3).toFloat(),
                    close = klineArray.getString(4).toFloat(),
                    volume = klineArray.getString(5).toFloat()
                )

                historicalKlines.add(klineData)
            }

            // 更新到主線程
            withContext(Dispatchers.Main) {
                klineHistory.clear()
                klineHistory.addAll(historicalKlines)
                _klineData.value = ArrayList(klineHistory)
                updateLoadingStatus("歷史數據載入完成 (${historicalKlines.size} 根)")
            }

        } catch (e: Exception) {
            println("解析歷史數據錯誤: ${e.message}")
        }
    }

    private fun connectPriceStream() {
        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/ws/btcusdt@trade")
            .build()

        priceWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updatePrice("Connected")
                updateLoadingStatus("即時價格已連接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val price = Regex("\"p\":\"(.*?)\"").find(text)?.groupValues?.get(1)
                    price?.let { updatePrice("$it USD") }
                } catch (e: Exception) {
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
                updateLoadingStatus("即時 K 線已連接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 在背景線程處理數據解析
                CoroutineScope(Dispatchers.IO).launch {
                    parseKlineData(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("K線連接失敗: ${t.message}")
                updateLoadingStatus("K線連接失敗")
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
                // 檢查是否是更新現有K線還是新增
                val existingIndex = klineHistory.indexOfLast {
                    it.openTime == newKline.openTime
                }

                if (existingIndex >= 0) {
                    // 更新現有的 K 線
                    klineHistory[existingIndex] = newKline
                    updateLoadingStatus("K線更新中... (${klineHistory.size} 根)")
                } else {
                    // 添加新的 K 線
                    klineHistory.add(newKline)

                    // 保持最多 50 根 K 線 (30分鐘歷史 + 20分鐘新增)
                    if (klineHistory.size > 50) {
                        klineHistory.removeAt(0)
                    }

                    updateLoadingStatus("新 K 線已加入 (${klineHistory.size} 根)")
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

    private fun updateLoadingStatus(status: String) {
        CoroutineScope(Dispatchers.Main).launch {
            _loadingStatus.value = status
        }
    }
}