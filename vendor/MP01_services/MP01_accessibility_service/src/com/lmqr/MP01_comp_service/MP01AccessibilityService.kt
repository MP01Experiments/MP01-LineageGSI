package com.lmqr.hMP01_comp_service

import SystemSettingsManager
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.lmqr.hMP01_comp_service.button_mapper.ButtonActionManager
import com.lmqr.hMP01_comp_service.command_runners.CommandRunner
import com.lmqr.hMP01_comp_service.command_runners.Commands
import com.lmqr.hMP01_comp_service.command_runners.UnixSocketCommandRunner
import kotlin.math.max


class MP01AccessibilityService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var commandRunner: CommandRunner
    private lateinit var refreshModeManager: RefreshModeManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var buttonActionManager: ButtonActionManager
    private lateinit var brightnessManager: BrightnessManager

    private var isScreenOn = true

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    menuBinding.close()
                    if (sharedPreferences.getBoolean("refresh_on_lock", false))
                        handler.postDelayed({
                            commandRunner.runCommands(arrayOf(Commands.SPEED_CLEAR))
                        }, 50)
                    brightnessManager.turnOffBrightness()
                    commandRunner.runCommands(arrayOf(Commands.FORCE_CLEAR, Commands.COMMIT_BITMAP))
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    if (sharedPreferences.getBoolean("refresh_on_lock", false))
                        refreshModeManager.applyMode()
                    brightnessManager.applyBrightness()
                }
            }
        }
    }
    private val receiverEink: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if(!sharedPreferences.getBoolean("allow_custom_broadcast", false))
                return

            when (intent.action) {
                "EINK_FORCE_CLEAR" -> {
                    commandRunner.runCommands(arrayOf(Commands.FORCE_CLEAR))
                }

                //"EINK_REFRESH_SPEED_CLEAR" -> {
                //    refreshModeManager.changeMode(RefreshMode.CLEAR)
                //}

                "EINK_REFRESH_SPEED_BALANCED" -> {
                    refreshModeManager.changeMode(RefreshMode.BALANCED)
                }

                "EINK_REFRESH_SPEED_SMOOTH" -> {
                    refreshModeManager.changeMode(RefreshMode.SMOOTH)
                }

                "EINK_REFRESH_SPEED_FAST" -> {
                    refreshModeManager.changeMode(RefreshMode.SPEED)
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        commandRunner =
            UnixSocketCommandRunner()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        refreshModeManager = RefreshModeManager(
            sharedPreferences,
            commandRunner
        )
        buttonActionManager = ButtonActionManager(commandRunner, refreshModeManager)
        brightnessManager = BrightnessManager(sharedPreferences, commandRunner)

        val filterScreen = IntentFilter()
        filterScreen.addAction(Intent.ACTION_SCREEN_ON)
        filterScreen.addAction(Intent.ACTION_SCREEN_OFF)
        registerReceiver(receiver, filterScreen)

        val filterEink = IntentFilter()
        filterEink.addAction("EINK_FORCE_CLEAR")
        //filterEink.addAction("EINK_REFRESH_SPEED_CLEAR")
        filterEink.addAction("EINK_REFRESH_SPEED_BALANCED")
        filterEink.addAction("EINK_REFRESH_SPEED_SMOOTH")
        filterEink.addAction("EINK_REFRESH_SPEED_FAST")
        registerReceiver(receiverEink, filterEink, RECEIVER_EXPORTED)
    }

    override fun onInterrupt() {

    }

    private val hardwareGestureDetector = HardwareGestureDetector(
        object: HardwareGestureDetector.OnGestureListener{
            override fun onSinglePress() {
                if(isScreenOn)
                    buttonActionManager.executeSinglePress(this@MP01AccessibilityService)
                else
                    buttonActionManager.executeSinglePressScreenOff(this@MP01AccessibilityService)
            }

            override fun onDoublePress() {
                if(isScreenOn)
                    buttonActionManager.executeDoublePress(this@MP01AccessibilityService)
                else
                    buttonActionManager.executeDoublePressScreenOff(this@MP01AccessibilityService)
            }

            override fun onLongPress() {
                if(isScreenOn)
                    buttonActionManager.executeLongPress(this@MP01AccessibilityService)
                else
                    buttonActionManager.executeLongPressScreenOff(this@MP01AccessibilityService)
            }
        }
    )

    private val hardwareGestureDetectorTop = HardwareGestureDetector(
        object: HardwareGestureDetector.OnGestureListener{
            override fun onSinglePress() {
                if(isScreenOn)
                    buttonActionManager.executeSinglePressTop(this@MP01AccessibilityService)
                else
                    buttonActionManager.executeSinglePressScreenOffTop(this@MP01AccessibilityService)
            }

            override fun onDoublePress() {
                if(isScreenOn)
                    buttonActionManager.executeDoublePressTop(this@MP01AccessibilityService)
                else
                    buttonActionManager.executeDoublePressScreenOffTop(this@MP01AccessibilityService)
            }

            override fun onLongPress() {
                if(isScreenOn)
                    buttonActionManager.executeLongPressTop(this@MP01AccessibilityService)
                else
                    buttonActionManager.executeLongPressScreenOffTop(this@MP01AccessibilityService)
            }
        }
    )

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if(event.scanCode == 252){
            if (!Settings.canDrawOverlays(baseContext))
                    requestOverlayPermission()
            else
                hardwareGestureDetector.onKeyEvent(event.action, event.eventTime)
        }
        return super.onKeyEvent(event)
    }


    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e("OverlayPermission", "Activity not found exception", e)
        }
    }

    private fun getAppUsableScreenSize(context: Context): Point {
        val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        return size
    }

    private fun getRealScreenSize(context: Context): Point {
        val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        return size
    }

    private fun getNavBarHeight(): Int {
        val appUsableSize: Point = getAppUsableScreenSize(this)
        val realScreenSize: Point = getRealScreenSize(this)

        if (appUsableSize.y < realScreenSize.y)
            return (realScreenSize.y - appUsableSize.y)

        return 0
    }

    private var menuBinding: FloatingMenuViewAccessor? = null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun openFloatingMenu() {
        try {
            menuBinding?.run {
                if (root.visibility == View.VISIBLE) {
                    root.visibility = View.GONE
                } else {
                    root.visibility = View.VISIBLE
                    updateButtons(refreshModeManager.currentMode)
                }
            } ?: run {

                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val inflater = LayoutInflater.from(this)
                val view = inflater.inflate(R.layout.floating_menu_layout, null, false)

                val layoutParams = WindowManager.LayoutParams().apply {
                    type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    format = PixelFormat.TRANSLUCENT
                    flags =
                        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    gravity = Gravity.BOTTOM
                    y = getNavBarHeight()
                }

                menuBinding = FloatingMenuViewAccessor(view).apply {
                    root.setOnTouchListener { v: View, event: MotionEvent ->
                        if (event.action == MotionEvent.ACTION_OUTSIDE)
                            close()
                        false
                    }

                    //button1.setOnClickListener {
                    //    refreshModeManager.changeMode(RefreshMode.CLEAR)
                    //    updateButtons(refreshModeManager.currentMode)
                    //}
                    button2.setOnClickListener {
                        refreshModeManager.changeMode(RefreshMode.BALANCED)
                        updateButtons(refreshModeManager.currentMode)
                    }
                    button3.setOnClickListener {
                        refreshModeManager.changeMode(RefreshMode.SMOOTH)
                        updateButtons(refreshModeManager.currentMode)
                    }
                    button4.setOnClickListener {
                        refreshModeManager.changeMode(RefreshMode.SPEED)
                        updateButtons(refreshModeManager.currentMode)
                    }



                    settingsIcon.setOnClickListener {
                        val settingsIntent = Intent(
                            this@MP01AccessibilityService,
                            SettingsActivity::class.java
                        )
                        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        close()
                        startActivity(settingsIntent)
                    }


                    brightnessManager.setupSeekBars(lightSeekbar, lightWarmSeekbar, lightKeyboardSeekbar)
                    updateButtons(refreshModeManager.currentMode)

                    wm.addView(root, layoutParams)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    override fun onDestroy() {
        commandRunner.onDestroy()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("MP01Service", "Service connected - applying all settings")
        
        handler.postDelayed({
            refreshModeManager.applyMode()
            Log.d("MP01Service", "Applied refresh mode: ${refreshModeManager.currentMode}")
            
            handler.postDelayed({
                brightnessManager.applyBrightness()
                Log.d("MP01Service", "Applied brightness: cold=${brightnessManager.coldBrightness}, warm=${brightnessManager.warmBrightness}")
                
                handler.postDelayed({
                    updateColorScheme(sharedPreferences)
                    Log.d("MP01Service", "Applied remaining settings")
                }, 500)
            }, 500)
        }, 1000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event.letPackageNameClassName { pkgName, clsName ->
            val componentName = ComponentName(
                pkgName,
                clsName
            )
            try {
                packageManager.getActivityInfo(componentName, 0)
                refreshModeManager.onAppChange(pkgName)
                menuBinding.updateButtons(refreshModeManager.currentMode)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
    }

    private fun updateColorScheme(sharedPreferences: SharedPreferences) = sharedPreferences.run {
        val type = getString("color_scheme_type", "5")
        val colorString = getInt("color_scheme_color", 20).progressToHex()
        commandRunner.runCommands(arrayOf("theme $type $colorString"))
    }

    private fun updateMaxBrightness(sharedPreferences: SharedPreferences) = sharedPreferences.run {
        try{
            val brightness = Integer.parseInt(getString("override_max_brightness", "2000")?:"2000")
            commandRunner.runCommands(arrayOf("smb$brightness"))
        } catch (e: NumberFormatException) {
            e.printStackTrace()
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "override_max_brightness" -> {
                sharedPreferences?.let { updateMaxBrightness(it) }
            }

            "color_scheme_type", "color_scheme_color" -> {
                sharedPreferences?.let { updateColorScheme(it) }
            }

            "close_status_bar" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }

            "run_clear_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                handler.postDelayed({
                    commandRunner.runCommands(arrayOf(Commands.FORCE_CLEAR))
                }, 700)
            }
        }
    }
}

fun AccessibilityEvent?.letPackageNameClassName(block: (String, String) -> Unit) {
    this?.run {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            this.className?.let { clsName ->
                this.packageName?.let { pkgName -> block(pkgName.toString(), clsName.toString()) }
            }
    }
}

fun Button.deselect() {
    setBackgroundResource(R.drawable.drawable_border_normal)
    setTextColor(Color.BLACK)
}

fun Button.select() {
    setBackgroundResource(R.drawable.drawable_border_pressed)
    setTextColor(Color.WHITE)
}