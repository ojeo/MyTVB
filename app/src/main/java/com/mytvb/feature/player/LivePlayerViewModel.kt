package com.mytvb.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.player.danmaku.LiveDanmakuManager
import com.mytvb.model.dm.DmModel
import com.mytvb.model.live.LiveDUrlModel
import com.mytvb.repository.LiveRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class LivePlayerViewModel(
    private val repository: LiveRepository,
    private val danmakuManager: LiveDanmakuManager
) : ViewModel() {

    companion object {
        private const val TAG = "LivePlayerViewModel"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        /** 直播弹幕聚合窗口：主线程批次频率与"每条弹幕额外延迟"的折中。 */
        private const val LIVE_DANMAKU_BATCH_WINDOW_MS = 16L

        /** 单批上限：防止极端洪峰下一次性构造过大列表（超出部分留给下一批）。 */
        private const val LIVE_DANMAKU_BATCH_MAX = 64
    }

    private val _playUrl = MutableStateFlow<String?>(null)
    val playUrl: StateFlow<String?> = _playUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _qualities = MutableStateFlow<List<LiveQualityInfo>>(emptyList())
    val qualities: StateFlow<List<LiveQualityInfo>> = _qualities.asStateFlow()
    private val _selectedQuality = MutableStateFlow<LiveQualityInfo?>(null)
    val selectedQuality: StateFlow<LiveQualityInfo?> = _selectedQuality.asStateFlow()

    private val _lines = MutableStateFlow<List<LiveLineInfo>>(emptyList())
    val lines: StateFlow<List<LiveLineInfo>> = _lines.asStateFlow()

    private val _selectedLineIndex = MutableStateFlow(0)
    val selectedLineIndex: StateFlow<Int> = _selectedLineIndex.asStateFlow()

    private val _liveDuration = MutableStateFlow("")
    val liveDuration: StateFlow<String> = _liveDuration.asStateFlow()

    private val _roomTitle = MutableStateFlow("")
    val roomTitle: StateFlow<String> = _roomTitle.asStateFlow()

    private val _anchorName = MutableStateFlow("")
    val anchorName: StateFlow<String> = _anchorName.asStateFlow()

    private val _refreshEvent = Channel<String>(Channel.BUFFERED)
    val refreshEvent = _refreshEvent.receiveAsFlow()

    // 弹幕
    private val _liveDanmakuBatches = MutableSharedFlow<List<DmModel>>(extraBufferCapacity = 8)
    val liveDanmakuBatches: SharedFlow<List<DmModel>> = _liveDanmakuBatches.asSharedFlow()
    val danmakuConnected: StateFlow<Boolean> = danmakuManager.isConnected

    /**
     * 直播弹幕聚合：把逐条到达的弹幕按 ~16ms 窗口攒成批，再整批交给播放器视图。
     *
     * 为什么值得聚合：WS 收包线程逐条 `tryEmit`，热门房间上限约 300 条/秒；逐条跨线程
     * 投递到主线程、逐条走一遍"启用判断 + 限流 + 密度预算 + 发射调度"，等于把大量细碎
     * 调度压在主线程上。聚合后主线程的批次数降到约 60 次/秒量级。
     *
     * 代价：每条弹幕多 ~16ms 延迟。引擎侧自身的发射批处理窗口是 50ms（LIVE_EMIT_BATCH_MS），
     * 且节流上限是 100ms/30 条，因此这点增量在量级上可忽略。
     *
     * 无元素丢失：用 UNLIMITED 通道做中转、`receive()` 阻塞取首条，
     * 不使用带超时的 `receive()`（超时取消可能吞掉已投递的元素）。
     */
    init {
        viewModelScope.launch {
            val inbox = Channel<DmModel>(Channel.UNLIMITED)
            launch { danmakuManager.danmakuFlow.collect { inbox.trySend(it) } }
            val batch = ArrayList<DmModel>(LIVE_DANMAKU_BATCH_MAX)
            while (isActive) {
                batch.clear()
                batch.add(inbox.receive())
                delay(LIVE_DANMAKU_BATCH_WINDOW_MS)
                while (batch.size < LIVE_DANMAKU_BATCH_MAX) {
                    batch.add(inbox.tryReceive().getOrNull() ?: break)
                }
                _liveDanmakuBatches.tryEmit(batch.toList())
            }
        }
    }

    private var currentRoomId: Long = 0
    private var heartbeatJob: Job? = null
    private var durationJob: Job? = null
    private var heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS

    fun loadLiveStream(roomId: Long) {
        currentRoomId = roomId
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            runCatching { repository.getIpInfo() }
                .onFailure { AppLog.w(TAG, "getIpInfo failed: ${it.message}") }

            repository.getLivePlayInfo(roomId).fold(
                onSuccess = { data ->
                    val durl = data.durl
                    if (!durl.isNullOrEmpty()) {
                        applyLines(durl)

                        AppLog.d(TAG, "loadLiveStream: liveTime=${data.liveTime} title=${data.roomTitle} anchor=${data.anchorName} qualities=${data.qualityDescription?.size}")
                        _roomTitle.value = data.roomTitle ?: ""
                        _anchorName.value = data.anchorName ?: ""
                        data.liveTime?.let { startLiveDuration(it) }

                        data.qualityDescription?.let { qualities ->
                            val mappedQualities = qualities.map {
                                LiveQualityInfo(it.qn, it.desc)
                            }.distinctBy { it.qn }
                            _qualities.value = mappedQualities
                            _selectedQuality.value = mappedQualities.firstOrNull {
                                it.qn == data.currentQn
                            } ?: mappedQualities.firstOrNull()
                        }
                    } else {
                        _error.value = "无法获取直播流地址"
                    }

                    launchSupplementaryCalls(roomId)

                    // 启动实时弹幕连接
                    danmakuManager.start(roomId)
                },
                onFailure = { e ->
                    _error.value = e.message ?: "加载直播失败"
                }
            )

            _isLoading.value = false
        }
    }

    private fun launchSupplementaryCalls(roomId: Long) {
        viewModelScope.launch {
            runCatching { repository.reportRoomEntry(roomId) }
                .onFailure { AppLog.w(TAG, "reportRoomEntry failed: ${it.message}") }
        }

        viewModelScope.launch {
            repository.getHeartbeatKey(roomId)
                .onSuccess { data ->
                    val intervalSec = runCatching { data["heartbeat_interval"]?.asLong }.getOrNull() ?: 60L
                    heartbeatIntervalMs = (intervalSec.coerceAtLeast(15L) * 1000L)
                }
                .onFailure { AppLog.w(TAG, "getHeartbeatKey failed: ${it.message}") }
        }

        viewModelScope.launch {
            runCatching { repository.getUserRoomInfo(roomId) }
                .onFailure { AppLog.w(TAG, "getUserRoomInfo failed: ${it.message}") }
        }

        // 历史弹幕（作为初始弹幕数据展示）
        viewModelScope.launch {
            repository.getDanmuHistory(roomId).onSuccess { jsonObj ->
                AppLog.d(TAG, "getDanmuHistory: received keys=${jsonObj.keySet()}")
            }.onFailure { AppLog.w(TAG, "getDanmuHistory failed: ${it.message}") }
        }

        startHeartbeat(roomId)
    }

    private fun startHeartbeat(roomId: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(heartbeatIntervalMs)
                runCatching { repository.sendLiveHeartbeat(roomId) }
                    .onFailure { AppLog.w(TAG, "sendLiveHeartbeat failed: ${it.message}") }
            }
        }
    }

    private fun startLiveDuration(liveTime: String) {
        durationJob?.cancel()
        val startMs = liveTime.toLongOrNull()?.let { it * 1000L }
        if (startMs == null) {
            AppLog.w(TAG, "startLiveDuration: failed to parse liveTime=$liveTime")
            return
        }
        AppLog.d(TAG, "startLiveDuration: liveTime=$liveTime startMs=$startMs")
        durationJob = viewModelScope.launch {
            while (true) {
                val elapsed = (System.currentTimeMillis() - startMs) / 1000
                val h = elapsed / 3600
                val m = (elapsed % 3600) / 60
                val s = elapsed % 60
                val days = h / 24
                val remainHours = h % 24
                _liveDuration.value = when {
                    days > 0 -> String.format(Locale.getDefault(), "%dD %02d:%02d:%02d", days, remainHours, m, s)
                    h > 0   -> String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
                    else    -> String.format(Locale.getDefault(), "%02d:%02d", m, s)
                }
                delay(1000)
            }
        }
    }

    /** 把 durl 列表刷新到 lines，重置选中为 0，并同步 _playUrl。 */
    private fun applyLines(durl: List<LiveDUrlModel>?) {
        val mapped = durl.orEmpty().mapIndexed { idx, item ->
            LiveLineInfo(
                index = idx,
                name = item.cdnName.ifBlank { "线路${idx + 1}" },
                url = item.url
            )
        }
        _lines.value = mapped
        _selectedLineIndex.value = 0
        mapped.firstOrNull()?.let { _playUrl.value = it.url }
    }

    fun switchQuality(qn: Int) {
        if (currentRoomId > 0) {
            viewModelScope.launch {
                repository.getLivePlayInfo(currentRoomId, qn).fold(
                    onSuccess = { data ->
                        _selectedQuality.value = _qualities.value.firstOrNull { it.qn == qn }
                        applyLines(data.durl)
                    },
                    onFailure = { e ->
                        _error.value = e.message ?: "切换画质失败"
                    }
                )
            }
        }
    }

    fun switchLine(index: Int) {
        val lines = _lines.value
        if (index !in lines.indices) return
        if (index == _selectedLineIndex.value) return
        _selectedLineIndex.value = index
        _playUrl.value = lines[index].url
    }

    fun refreshLiveStream(roomId: Long) {
        viewModelScope.launch {
            _error.value = null
            repository.getLivePlayInfo(roomId).fold(
                onSuccess = { data ->
                    applyLines(data.durl)
                    _refreshEvent.trySend("刷新成功")
                },
                onFailure = { e ->
                    _error.value = e.message ?: "刷新直播失败"
                }
            )
        }
    }

    fun retryLiveStream(roomId: Long) {
        val qn = _selectedQuality.value?.qn
        viewModelScope.launch {
            _error.value = null
            _isLoading.value = true
            repository.getLivePlayInfo(roomId, qn ?: 0).fold(
                onSuccess = { data ->
                    applyLines(data.durl)
                },
                onFailure = { e ->
                    _error.value = e.message ?: "重试失败"
                }
            )
            _isLoading.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        danmakuManager.release()
        heartbeatJob?.cancel()
        heartbeatJob = null
        durationJob?.cancel()
        durationJob = null
    }
}

data class LiveQualityInfo(
    val qn: Int,
    val desc: String
)

data class LiveLineInfo(
    val index: Int,
    val name: String,
    val url: String
)
