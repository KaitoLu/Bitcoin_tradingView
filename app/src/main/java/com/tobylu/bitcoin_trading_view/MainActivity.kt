package com.tobylu.bitcoin_trading_view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tobylu.bitcoin_trading_view.databinding.ActivityMainBinding
import com.tobylu.bitcoin_trading_view.network.BTCWebSocketManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val btcWebSocketManager = BTCWebSocketManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 開始 WebSocket 連線
        btcWebSocketManager.connect()

        // 收集價格並更新 UI
        lifecycleScope.launch {
            btcWebSocketManager.btcPrice.collectLatest { price ->
                binding.priceTextView.text = price
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        btcWebSocketManager.disconnect()
    }
}