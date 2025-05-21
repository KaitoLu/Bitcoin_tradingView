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

class BTCWebSocketManager {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val _btcPrice = MutableStateFlow("Connecting...")
    val btcPrice: StateFlow<String> get() = _btcPrice

    fun connect() {
        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/ws/btcusdt@trade")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updatePrice("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Parse JSON to extract price
                val price = Regex("\"p\":\"(.*?)\"").find(text)?.groupValues?.get(1)
                price?.let { updatePrice("$it USD") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updatePrice("Connection failed: \${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updatePrice("Connection closed")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, null)
    }

    private fun updatePrice(price: String) {
        CoroutineScope(Dispatchers.Main).launch {
            _btcPrice.value = price
        }
    }
}