package com.tobylu.bitcoin_trading_view

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.CandleStickChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.tobylu.bitcoin_trading_view.databinding.ActivityMainBinding
import com.tobylu.bitcoin_trading_view.network.BTCWebSocketManager
import com.tobylu.bitcoin_trading_view.network.KlineData
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val btcWebSocketManager = BTCWebSocketManager()
    private lateinit var candleChart: CandleStickChart

    // 用於 X 軸標籤顯示的數據引用
    private var klineDataForDisplay = listOf<KlineData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 K 線圖
        setupCandleChart()

        // 開始 WebSocket 連線
        btcWebSocketManager.connect()

        // 收集價格並更新 UI
        lifecycleScope.launch {
            btcWebSocketManager.btcPrice.collectLatest { price ->
                binding.priceTextView.text = price
                updateStatus("即時價格已更新")
            }
        }

        // 收集 K 線數據並更新圖表
        lifecycleScope.launch {
            btcWebSocketManager.klineData.collectLatest { klineList ->
                try {
                    if (klineList.isNotEmpty()) {
                        updateCandleChart(klineList)
                        updateStatus("K線數據: ${klineList.size} 根")
                    }
                } catch (e: Exception) {
                    println("處理 K 線數據錯誤: ${e.message}")
                    updateStatus("K線更新錯誤")
                }
            }
        }
    }

    private fun setupCandleChart() {
        candleChart = binding.candleStickChart

        // 基本設置
        candleChart.description.isEnabled = false
        candleChart.setMaxVisibleValueCount(50)
        candleChart.setPinchZoom(true)
        candleChart.setDrawGridBackground(false)
        candleChart.setBackgroundColor(Color.BLACK)
        candleChart.setScaleEnabled(true)  // 允許縮放
        candleChart.isDragEnabled = true   // 允許拖拽

        // X軸設置
        val xAxis = candleChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = Color.GRAY
        xAxis.textColor = Color.WHITE
        xAxis.granularity = 1f  // 設置 X 軸間距
        xAxis.setLabelCount(6, false)  // 最多顯示 6 個標籤

        // Y軸設置
        val yAxisLeft = candleChart.axisLeft
        yAxisLeft.setDrawGridLines(true)
        yAxisLeft.gridColor = Color.GRAY
        yAxisLeft.textColor = Color.WHITE
        yAxisLeft.setDrawAxisLine(true)
        yAxisLeft.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART)

        val yAxisRight = candleChart.axisRight
        yAxisRight.isEnabled = false

        // 圖例設置
        candleChart.legend.isEnabled = false

        // 設置圖表邊距，確保蠟燭能正常顯示
        candleChart.setExtraOffsets(10f, 10f, 10f, 10f)
    }

    private fun updateCandleChart(klineList: List<KlineData>) {
        // 保存數據供 X 軸標籤使用
        klineDataForDisplay = klineList

        val entries = mutableListOf<CandleEntry>()

        // 使用索引作為 X 軸位置，而不是時間戳
        klineList.forEachIndexed { index, kline ->
            entries.add(
                CandleEntry(
                    index.toFloat(),           // X 軸位置 (索引)
                    kline.high,                // 最高價
                    kline.low,                 // 最低價
                    kline.open,                // 開盤價
                    kline.close                // 收盤價
                )
            )
        }

        val dataSet = CandleDataSet(entries, "BTC/USDT").apply {
            // 顏色設置
            shadowColor = Color.GRAY
            shadowWidth = 0.7f
            decreasingColor = Color.RED      // 下跌顏色 (紅色)
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = Color.GREEN    // 上漲顏色 (綠色)
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            neutralColor = Color.GRAY        // 平盤顏色

            // 禁用數值顯示
            setDrawValues(false)

            // 這個才是正確的蠟燭間距設置
            barSpace = 0.3f  // 蠟燭之間的間距，數值越大間距越大
        }

        val candleData = CandleData(dataSet)
        candleChart.data = candleData

        // 設置可見範圍，避免圖表被壓縮
        when {
            entries.size == 1 -> {
                // 只有一根蠟燭時，設置較大的顯示範圍
                candleChart.setVisibleXRange(0f, 10f)  // 顯示 0-10 的範圍
                candleChart.moveViewToX(0f)
            }
            entries.size <= 5 -> {
                // 少於 5 根時，固定顯示 10 根的範圍
                candleChart.setVisibleXRange(0f, 10f)
                candleChart.moveViewToX((entries.size - 1).toFloat())
            }
            else -> {
                // 多於 5 根時，正常顯示
                candleChart.setVisibleXRangeMaximum(20f)
                candleChart.moveViewToX((entries.size - 1).toFloat())
            }
        }

        // 刷新圖表
        candleChart.invalidate()
    }

    private fun updateStatus(status: String) {
        binding.statusTextView.text = status
    }

    override fun onDestroy() {
        super.onDestroy()
        btcWebSocketManager.disconnect()
    }
}