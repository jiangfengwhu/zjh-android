package com.zijinhua.app

import android.content.Intent
import com.swmansion.rnscreens.fragment.restoration.RNScreensFragmentFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Gravity
import android.content.res.ColorStateList
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.facebook.react.ReactHost
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.zijinhua.app.plugin.PluginCenter
import com.zijinhua.app.shell.ShellAi
import com.zijinhua.app.shell.ShellCapabilities

// react-native-screens requires AppCompatActivity; RN is hosted directly in a native view.
class MainActivity : AppCompatActivity(), DefaultHardwareBackBtnHandler, PermissionAwareActivity {
  private val permissionListeners = mutableMapOf<Int, PermissionListener>()

  override fun requestPermissions(permissions: Array<String>, requestCode: Int, listener: PermissionListener?) {
    if (listener != null) permissionListeners[requestCode] = listener
    super.requestPermissions(permissions, requestCode)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    val listener = permissionListeners.remove(requestCode)
    listener?.onRequestPermissionsResult(requestCode, permissions.map { it }.toTypedArray(), grantResults)
  }

  private val reactHost: ReactHost
    get() = (application as MainApplication).reactHost

  private var surface: ReactSurface? = null
  private var canvas = Color.parseColor("#13100E")
  private lateinit var root: FrameLayout
  private var loadingPanel: LinearLayout? = null
  private var loadingLabel: TextView? = null
  private var edgesPinned = false
  private var appliedLight: Boolean? = null
  private var appliedBackdrop: Int? = null
  private var splashHeld = true
  private var loadingWanted = false
  private val loadingHandler = Handler(Looper.getMainLooper())
  private val revealLoading = Runnable {
    if (loadingWanted) {
      showLoading()
      Log.i(TAG, "bundle loading visible")
    }
  }

  private val bundleLoadingListener: (Boolean) -> Unit = { loading ->
    if (splashHeld) {
      if (!loading) splashHeld = false
    } else if (loading) {
      loadingWanted = true
      loadingHandler.removeCallbacks(revealLoading)
      loadingHandler.postDelayed(revealLoading, LOADING_REVEAL_DELAY_MS)
    } else {
      loadingWanted = false
      loadingHandler.removeCallbacks(revealLoading)
      loadingPanel?.visibility = View.GONE
    }
  }
  private val surfaceChangedListener: () -> Unit = { runOnUiThread { applySystemBars() } }

  private val mainComponentName: String = "Zijinhua"

  override fun onCreate(savedInstanceState: Bundle?) {
    val splash = installSplashScreen()
    supportFragmentManager.fragmentFactory = RNScreensFragmentFactory()
    super.onCreate(savedInstanceState)
    splash.setKeepOnScreenCondition { splashHeld }
    splash.setOnExitAnimationListener { screen ->
      screen.remove()
      Log.i(TAG, "splash removed")
    }
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    initializeSystemBars()
    PluginCenter.onBundleLoading = bundleLoadingListener
    ShellAi.onSurfaceChanged = surfaceChangedListener
    applySystemBars()
    ShellCapabilities.attach(this)
    onBackPressedDispatcher.addCallback(this, BackCallback())
    root = FrameLayout(this).apply { setBackgroundColor(canvas) }
    setContentView(root)
    val created = reactHost.createSurface(this, mainComponentName, null)
    attachSurface(created)
    val view = created.view ?: throw IllegalStateException("ReactSurface has no view")
    view.setBackgroundColor(canvas)
    // React KeyboardProvider owns IME avoidance; keep the root geometry fixed.
    root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    created.start()
    Log.i(TAG, "react surface started component=$mainComponentName")
    Log.i(TAG, "activity created")
  }

  @Deprecated("Activity result API is used by React Native libraries")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    reactHost.onActivityResult(this, requestCode, resultCode, data)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    reactHost.onNewIntent(intent)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    reactHost.onWindowFocusChange(hasFocus)
    if (hasFocus) applySystemBars()
  }

  override fun onResume() {
    super.onResume()
    applySystemBars()
    if (surface != null) {
      reactHost.onHostResume(this, this)
    }
  }

  override fun onPause() {
    if (surface != null) {
      reactHost.onHostPause(this)
    }
    super.onPause()
  }

  override fun onDestroy() {
    loadingHandler.removeCallbacks(revealLoading)
    if (PluginCenter.onBundleLoading === bundleLoadingListener) PluginCenter.onBundleLoading = null
    if (ShellAi.onSurfaceChanged === surfaceChangedListener) ShellAi.onSurfaceChanged = null
    surface?.stop()
    surface = null
    reactHost.onHostDestroy(this)
    ShellCapabilities.detach(this)
    super.onDestroy()
  }

  private fun attachSurface(created: ReactSurface) {
    surface = created
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
      reactHost.onHostResume(this, this)
    }
  }

  private fun showLoading() {
    val panel = loadingPanel ?: LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(canvas)
      val spinner = ProgressBar(this@MainActivity).apply {
        indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
      }
      val size = (48 * resources.displayMetrics.density).toInt()
      addView(spinner, LinearLayout.LayoutParams(size, size))
      val label = TextView(this@MainActivity).apply {
        text = "正在加载应用"
        textSize = 15f
        setTextColor(if (ShellAi.lightSurface) Color.parseColor("#0B111E") else Color.parseColor("#F1F5F9"))
      }
      addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (16 * resources.displayMetrics.density).toInt()
      })
      loadingLabel = label
      root.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
      loadingPanel = this
    }
    panel.visibility = View.VISIBLE
  }

  private fun canvasColor(): Int {
    return if (ShellAi.lightSurface) Color.parseColor("#FFF9ED") else Color.parseColor("#19152C")
  }

  private fun applySystemBars() {
    val light = ShellAi.lightSurface
    val backdrop = canvasColor()
    if (canvas != backdrop) {
      canvas = backdrop
      if (::root.isInitialized) root.setBackgroundColor(backdrop)
      surface?.view?.setBackgroundColor(backdrop)
      loadingPanel?.setBackgroundColor(backdrop)
    }
    loadingLabel?.setTextColor(if (light) Color.parseColor("#0B111E") else Color.parseColor("#F1F5F9"))
    if (appliedLight != light || appliedBackdrop != backdrop) {
      appliedLight = light
      appliedBackdrop = backdrop
      window.setBackgroundDrawable(ColorDrawable(backdrop))
      WindowInsetsControllerCompat(window, window.decorView).let { controller ->
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
      }
      Log.i(TAG, "system bars light=$light status=transparent navigation=transparent backdrop=${Integer.toHexString(backdrop)}")
    }
    val bars = WindowInsetsControllerCompat(window, window.decorView)
    // Navigation is a window-level policy, independent of page and tab scrolling.
    bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    if (ShellAi.immersive) {
      bars.hide(WindowInsetsCompat.Type.statusBars())
    } else {
      bars.show(WindowInsetsCompat.Type.statusBars())
    }
    pinContentToScreenEdges()
  }

  @Suppress("DEPRECATION")
  private fun initializeSystemBars() {
    // One window policy for the whole app. Pages and IME never rewrite it.
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.navigationBarDividerColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isStatusBarContrastEnforced = false
      window.isNavigationBarContrastEnforced = false
    }
    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.navigationBars())
  }

  /** 全屏容器延伸至屏幕边缘，导航栏透明且保持可见，内容仍延伸至屏幕边缘。 */
  private fun pinContentToScreenEdges() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (edgesPinned) return
    val content = findViewById<View>(android.R.id.content) ?: return
    var current: View? = content
    while (current != null) {
      if (current.paddingLeft != 0 || current.paddingTop != 0 || current.paddingRight != 0 || current.paddingBottom != 0) {
        current.setPadding(0, 0, 0, 0)
      }
      current.fitsSystemWindows = false
      val params = current.layoutParams
      if (params is android.view.ViewGroup.MarginLayoutParams &&
        (params.topMargin != 0 || params.bottomMargin != 0)
      ) {
        params.topMargin = 0
        params.bottomMargin = 0
        current.layoutParams = params
      }
      current = current.parent as? View
    }
    ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
      if (view.paddingTop != 0 || view.paddingBottom != 0 || view.paddingLeft != 0 || view.paddingRight != 0) {
        view.setPadding(0, 0, 0, 0)
      }
      insets
    }
    edgesPinned = true
    ViewCompat.requestApplyInsets(window.decorView)
  }

  override fun invokeDefaultOnBackPressed() {
    Log.i(TAG, "back unhandled, move task to back")
    moveTaskToBack(true)
  }

  private inner class BackCallback : androidx.activity.OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
      if (surface != null) {
        reactHost.onBackPressed()
      } else {
        moveTaskToBack(true)
      }
    }
  }

  private companion object {
    const val TAG = "MainActivity"
    const val LOADING_REVEAL_DELAY_MS = 400L
  }
}
