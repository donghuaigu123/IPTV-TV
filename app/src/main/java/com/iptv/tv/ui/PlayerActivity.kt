package com.iptv.tv.ui

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.video.VideoSize
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.iptv.tv.R
import com.iptv.tv.core.SourceCollector
import com.iptv.tv.data.PlaylistBus
import com.iptv.tv.data.Prefs
import com.iptv.tv.model.Channel
import kotlin.math.abs

@SuppressLint("UnsafeOptInUsageError")
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: StyledPlayerView
    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var httpFactory: DefaultHttpDataSource.Factory
    private lateinit var drawer: DrawerLayout
    private lateinit var adapter: ChannelAdapter
    private lateinit var tvTitle: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvCenterHint: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton

    private var channels: List<Channel> = emptyList()
    private var currentIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var locked = false
    private var brightness = 0.5f
    private var volumeStart = 0f
    private var seekStart = 0L
    private var isSeeking = false
    private var gestureMode = 0
    private var touchStartY = 0f
    private var touchStartX = 0f
    private var playerReleased = false

    // 遥控器数字选台缓冲
    private var numberBuffer = ""
    private val clearNumberTask = Runnable { numberBuffer = "" }

    private val updateTask = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && !playerReleased) {
                try { updateTime() } catch (_: Exception) {}
                handler.postDelayed(this, 500)
            }
        }
    }

    private val hideControlsTask = Runnable {
        if (!locked && controlsVisible) hideControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        channels = PlaylistBus.get().ifEmpty { Prefs.getChannels(this) }
        if (channels.isEmpty()) {
            val json = intent.getStringExtra("channels")
            if (!json.isNullOrBlank()) {
                channels = try {
                    Gson().fromJson(json, object : TypeToken<List<Channel>>() {}.type) ?: emptyList()
                } catch (e: Exception) { emptyList() }
            }
        }
        currentIndex = intent.getIntExtra("index", 0).coerceIn(0, (channels.size - 1).coerceAtLeast(0))

        findViews()
        setupDrawer()
        try {
            setupPlayer()
        } catch (e: Exception) {
            Toast.makeText(this, "播放器初始化失败：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setupControls()
        setupGestures()
        playCurrent()
        handler.post(updateTask)
        scheduleHideControls()
    }

    private fun findViews() {
        playerView = findViewById(R.id.playerView)
        drawer = findViewById(R.id.drawer)
        tvTitle = findViewById(R.id.tvTitle)
        tvTime = findViewById(R.id.tvTime)
        tvCenterHint = findViewById(R.id.tvCenterHint)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
    }

    private fun setupDrawer() {
        val recycler = findViewById<RecyclerView>(R.id.recyclerDrawer)
        adapter = ChannelAdapter(channels.toMutableList(), onPlay = { ch ->
            currentIndex = channels.indexOfFirst { it.url == ch.url && it.name == ch.name }.coerceAtLeast(0)
            playCurrent()
            drawer.closeDrawers()
        })
        adapter.darkTheme = true
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val etSearch = findViewById<EditText>(R.id.etChannelSearch)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().trim()
                adapter.submit(if (q.isBlank()) channels else channels.filter { it.name.contains(q, true) })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<View>(R.id.btnCloseDrawer).setOnClickListener { drawer.closeDrawers() }
    }

    private fun setupPlayer() {
        val ctx = this
        val ua = Prefs.getString(ctx, Prefs.KEY_UA, SourceCollector.DEFAULT_UA)
        httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        trackSelector = DefaultTrackSelector(ctx)

        val mode = when (Prefs.getInt(ctx, Prefs.KEY_DECODER, 0)) {
            1 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            2 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        val renderersFactory = DefaultRenderersFactory(ctx).setExtensionRendererMode(mode)

        val bufferMode = Prefs.getInt(ctx, Prefs.KEY_BUFFER, 0)
        val loadControl = when (bufferMode) {
            1 -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 5000, 1000, 1000)
                .setPrioritizeTimeOverSizeThresholds(true).build()
            2 -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 60000, 5000, 5000)
                .setPrioritizeTimeOverSizeThresholds(false).build()
            else -> DefaultLoadControl()
        }

        // DefaultDataSourceFactory 支持 http/https/content/file，内部用 httpFactory 提供 UA
        val dataSourceFactory = DefaultDataSourceFactory(ctx, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory())

        player = ExoPlayer.Builder(ctx, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (playerReleased || isFinishing || isDestroyed) return
                        try {
                            when (state) {
                                Player.STATE_BUFFERING -> tvCenterHint.apply { text = "缓冲中…"; visibility = View.VISIBLE }
                                Player.STATE_READY -> { tvCenterHint.visibility = View.GONE; updateTime() }
                                Player.STATE_ENDED -> { tvCenterHint.apply { text = "播放结束"; visibility = View.VISIBLE } }
                            }
                            updatePlayButton()
                        } catch (_: Exception) {}
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (playerReleased || isFinishing || isDestroyed) return
                        try {
                            updatePlayButton()
                            window.setFlags(
                                if (isPlaying) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            )
                        } catch (_: Exception) {}
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (playerReleased || isFinishing || isDestroyed) return
                        try {
                            tvCenterHint.apply { text = "播放失败：${error.message?.take(80)}"; visibility = View.VISIBLE }
                        } catch (_: Exception) {}
                    }
                    override fun onVideoSizeChanged(size: VideoSize) {}
                })
            }
        playerView.player = player
        playerView.setControllerVisibilityListener(StyledPlayerView.ControllerVisibilityListener { vis ->
            controlsVisible = vis == View.VISIBLE
        })
    }

    private fun playCurrent() {
        if (channels.isEmpty()) {
            Toast.makeText(this, "频道列表为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (playerReleased || !::player.isInitialized) return
        if (currentIndex !in channels.indices) currentIndex = 0
        val ch = channels[currentIndex]
        tvTitle.text = "${ch.name}  ·  ${ch.group}"
        Prefs.putInt(this, Prefs.KEY_LAST_PLAYED, currentIndex)

        val url = ch.url.trim()
        if (!url.contains("://")) {
            tvCenterHint.apply { text = "无效地址：${url.take(60)}"; visibility = View.VISIBLE }
            return
        }
        try {
            val ua = ch.userAgent.ifBlank { Prefs.getString(this, Prefs.KEY_UA, SourceCollector.DEFAULT_UA) }
            try { httpFactory.setUserAgent(ua) } catch (_: Exception) {}
            val item = MediaItem.Builder().setUri(Uri.parse(url)).setTag(ch).build()
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
            tvCenterHint.apply { text = "正在连接：${ch.name}"; visibility = View.VISIBLE }
            updatePlayButton()
        } catch (e: Exception) {
            tvCenterHint.apply { text = "播放地址解析失败：${e.message?.take(60)}"; visibility = View.VISIBLE }
        }
    }

    private fun setupControls() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnList).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<View>(R.id.btnLock).setOnClickListener {
            locked = !locked
            topBar.visibility = if (locked) View.GONE else View.VISIBLE
            bottomBar.visibility = if (locked) View.GONE else View.VISIBLE
            Toast.makeText(this, if (locked) "已锁定屏幕" else "已解锁", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMoreMenu() }
        btnPlayPause.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { switchChannel(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { switchChannel(1) }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPip() }
        findViewById<View>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !playerReleased && ::player.isInitialized) {
                    try {
                        if (player.duration > 0) tvTime.text = "${fmt(player.currentPosition)} / ${fmt(player.duration)}"
                    } catch (_: Exception) {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                if (playerReleased || !::player.isInitialized) return
                try {
                    val p = sb?.progress ?: 0
                    if (player.duration > 0) player.seekTo(player.duration * p / 1000)
                } catch (_: Exception) {}
            }
        })
    }

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) return true
                toggleControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                togglePlay()
                return true
            }
            override fun onLongPress(e: MotionEvent) { if (!locked) showChannelInfo() }
        })
        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x; touchStartY = event.y
                    volumeStart = getVolume()
                    seekStart = if (!playerReleased && ::player.isInitialized) {
                        try { player.currentPosition } catch (_: Exception) { 0L }
                    } else 0L
                    gestureMode = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (locked) return@setOnTouchListener false
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (gestureMode == 0 && (abs(dx) > 40 || abs(dy) > 40)) {
                        gestureMode = if (abs(dx) > abs(dy)) 3 else if (event.y < resources.displayMetrics.heightPixels / 2) 2 else 1
                    }
                    when (gestureMode) {
                        1 -> setVolume(volumeStart - dy / 200f)
                        2 -> setBrightness(brightness - dy / 400f)
                        3 -> {
                            if (playerReleased || !::player.isInitialized) return@setOnTouchListener false
                            try {
                                val dur = if (player.duration > 0) player.duration else 60_000L
                                val target = (seekStart + dx / 4 * 1000).toLong().coerceIn(0, dur)
                                player.seekTo(target)
                                tvCenterHint.apply { text = "快进到 ${fmt(target)}"; visibility = View.VISIBLE }
                            } catch (_: Exception) {}
                        }
                    }
                }
                MotionEvent.ACTION_UP -> { tvCenterHint.visibility = View.GONE; scheduleHideControls() }
            }
            false
        }
    }

    // ================= 遥控器深度优化 =================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (locked && event.action == KeyEvent.ACTION_DOWN) {
            // 锁屏时仅允许解锁：菜单键连按两次
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                Toast.makeText(this, "按菜单键解锁", Toast.LENGTH_SHORT).show()
                locked = false
                topBar.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && handleRemoteKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    /** 处理遥控器按键，返回 true 表示已消费 */
    private fun handleRemoteKey(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlay(); showControlsTemporarily(); return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                switchChannel(1); showControlsTemporarily(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                switchChannel(-1); showControlsTemporarily(); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-5_000); showControlsTemporarily(); return false }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(5_000); showControlsTemporarily(); return false }
            KeyEvent.KEYCODE_DPAD_UP -> { adjustVolume(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { adjustVolume(-1); return true }
            KeyEvent.KEYCODE_MENU -> { showMoreMenu(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showChannelInfo(); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (drawer.isDrawerOpen(GravityCompat.START)) { drawer.closeDrawers(); return true }
                if (controlsVisible) { hideControls(); return true }
                finish(); return true
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val digit = code - KeyEvent.KEYCODE_0
                numberBuffer += digit.toString()
                if (numberBuffer.length >= 3) {
                    jumpToChannel(numberBuffer.toIntOrNull() ?: 0)
                    numberBuffer = ""
                } else {
                    tvCenterHint.apply { text = numberBuffer; visibility = View.VISIBLE }
                    handler.removeCallbacks(clearNumberTask)
                    handler.postDelayed(clearNumberTask, 1200)
                }
                return true
            }
        }
        return false
    }

    private fun jumpToChannel(number: Int) {
        if (channels.isEmpty() || number < 1) { numberBuffer = ""; return }
        val idx = (number - 1).coerceIn(0, channels.size - 1)
        currentIndex = idx
        playCurrent()
        tvCenterHint.apply { text = "${channels[idx].name}（${number}）"; visibility = View.VISIBLE }
        handler.postDelayed({ tvCenterHint.visibility = View.GONE }, 1200)
        showControlsTemporarily()
    }

    private fun seekBy(deltaMs: Long) {
        if (playerReleased || !::player.isInitialized) return
        try {
            if (player.duration > 0) {
                val target = (player.currentPosition + deltaMs).coerceIn(0, player.duration)
                player.seekTo(target)
                tvCenterHint.apply {
                    text = "${if (deltaMs > 0) "快进" else "后退"} ${fmt(target)}"
                    visibility = View.VISIBLE
                }
                handler.postDelayed({ tvCenterHint.visibility = View.GONE }, 1200)
            } else {
                Toast.makeText(this, "直播流无法拖动进度", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    private fun adjustVolume(delta: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (cur + delta).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
        tvCenterHint.apply { text = "音量 ${next * 100 / max.coerceAtLeast(1)}%"; visibility = View.VISIBLE }
        handler.postDelayed({ tvCenterHint.visibility = View.GONE }, 1000)
    }

    private fun showControlsTemporarily() {
        showControls()
        handler.removeCallbacks(hideControlsTask)
        scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsTask)
        handler.postDelayed(hideControlsTask, 5000)
    }

    private fun togglePlay() {
        if (playerReleased || !::player.isInitialized) return
        try {
            if (player.isPlaying) player.pause() else player.play()
            updatePlayButton()
        } catch (_: Exception) {}
    }

    private fun updatePlayButton() {
        if (playerReleased || !::player.isInitialized) return
        try {
            btnPlayPause.setImageResource(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        } catch (_: Exception) {}
    }

    private fun switchChannel(delta: Int) {
        if (channels.isEmpty()) return
        currentIndex = (currentIndex + delta + channels.size) % channels.size
        playCurrent()
    }

    private fun updateTime() {
        if (isSeeking || playerReleased || !::player.isInitialized) return
        try {
            val pos = player.currentPosition
            val dur = player.duration
            if (dur > 0) {
                seekBar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
                tvTime.text = "${fmt(pos)} / ${fmt(dur)}"
            } else {
                seekBar.progress = 0
                tvTime.text = "${fmt(pos)} / 直播"
            }
        } catch (_: Exception) {}
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun toggleControls() { if (controlsVisible) hideControls() else showControls() }

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        // 焦点给到播放/暂停，方便遥控器直接操作
        btnPlayPause.requestFocus()
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    private fun getVolume(): Float {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    private fun setVolume(v: Float) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).toInt(), 0)
        tvCenterHint.apply { text = "音量 ${(v * 100).toInt()}%"; visibility = View.VISIBLE }
    }

    private fun setBrightness(v: Float) {
        brightness = v.coerceIn(0.05f, 1f)
        try {
            val lp = window.attributes
            lp.screenBrightness = brightness
            window.attributes = lp
        } catch (_: Exception) {}
        tvCenterHint.apply { text = "亮度 ${(brightness * 100).toInt()}%"; visibility = View.VISIBLE }
    }

    private fun showMoreMenu() {
        val items = arrayOf("倍速播放", "音轨选择", "字幕选择", "画面比例", "复制当前地址", "频道详情", "遥控器帮助")
        AlertDialog.Builder(this)
            .setTitle("播放设置")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showSpeedDialog()
                    1 -> showTrackDialog(C.TRACK_TYPE_AUDIO)
                    2 -> showTrackDialog(C.TRACK_TYPE_TEXT)
                    3 -> showRatioDialog()
                    4 -> copyCurrentUrl()
                    5 -> showChannelInfo()
                    6 -> showRemoteHelp()
                }
            }.show()
    }

    private fun showRemoteHelp() {
        AlertDialog.Builder(this)
            .setTitle("遥控器操作")
            .setMessage(
                "确认键：显示控制栏 / 播放暂停\n" +
                "数字键：输入频道号直接跳转（如 1、0、5）\n" +
                "左/右：后退 / 快进 5 秒\n" +
                "上/下：音量 +/- \n" +
                "频道 +/- 或媒体上一首/下一首：切换频道\n" +
                "快进/快退键：±10 秒\n" +
                "菜单键：播放设置\n" +
                "信息键：频道详情\n" +
                "返回键：隐藏控制栏 / 退出"
            )
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this).setTitle("倍速播放").setItems(speeds) { _, i ->
            if (!playerReleased && ::player.isInitialized) {
                try {
                    player.setPlaybackSpeed(values[i])
                    Toast.makeText(this, "已设为 ${speeds[i]}", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }.show()
    }

    private fun showTrackDialog(type: Int) {
        if (playerReleased || !::player.isInitialized) return
        val groups = try { player.currentTracks.groups.filter { it.type == type } } catch (_: Exception) { emptyList() }
        val labels = mutableListOf<String>("关闭")
        val selections = mutableListOf<Pair<Tracks.Group, Int>?>(null)
        groups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label ?: format.language ?: "轨道"
                labels += "$label (${format.sampleMimeType?.substringAfter('/') ?: "未知"})"
                selections += group to i
            }
        }
        AlertDialog.Builder(this).setTitle(if (type == C.TRACK_TYPE_AUDIO) "选择音轨" else "选择字幕")
            .setItems(labels.toTypedArray()) { _, i ->
                val sel = selections.getOrNull(i)
                if (sel == null) clearTracks(type) else selectTrack(sel.first, sel.second)
            }.show()
    }

    private fun clearTracks(type: Int) {
        val b = trackSelector.buildUponParameters().setTrackTypeDisabled(type, true)
        trackSelector.setParameters(b)
    }

    private fun selectTrack(group: Tracks.Group, index: Int) {
        try {
            val override = TrackSelectionOverride(group.mediaTrackGroup, index)
            val b = trackSelector.buildUponParameters()
                .setTrackTypeDisabled(group.type, false)
                .setOverrideForType(override)
            trackSelector.setParameters(b)
        } catch (e: Exception) {
            Toast.makeText(this, "切换轨道失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRatioDialog() {
        val items = arrayOf("适应屏幕", "填满屏幕", "裁剪填充")
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        AlertDialog.Builder(this).setTitle("画面比例").setItems(items) { _, i ->
            playerView.resizeMode = modes[i]
        }.show()
    }

    private fun copyCurrentUrl() {
        val ch = channels.getOrNull(currentIndex) ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("IPTV", ch.url))
        Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show()
    }

    private fun showChannelInfo() {
        val ch = channels.getOrNull(currentIndex) ?: return
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setMessage("分组：${ch.group}\n来源：${ch.source}\n地址：${ch.url}\n状态：${if (ch.isOk) "可用" else "未检测"}")
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (e: Exception) {
                Toast.makeText(this, "当前设备不支持画中画", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "需要 Android 8.0 及以上", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onPictureInPictureModeChanged(isInPic: Boolean) {
        super.onPictureInPictureModeChanged(isInPic)
        if (isInPic) hideControls() else showControls()
    }

    override fun onStop() {
        super.onStop()
        try {
            if (::player.isInitialized && !playerReleased && Build.VERSION.SDK_INT >= 24 && !isInPictureInPictureMode) {
                player.pause()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        playerReleased = true
        handler.removeCallbacks(updateTask)
        handler.removeCallbacks(hideControlsTask)
        handler.removeCallbacks(clearNumberTask)
        try {
            if (::player.isInitialized) {
                playerView.player = null
                player.release()
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
