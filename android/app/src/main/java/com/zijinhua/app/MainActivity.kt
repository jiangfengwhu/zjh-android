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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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

// react-native-screens 的原生栈会把 Activity 转成 AppCompatActivity。它同时是 FragmentActivity，Compose 的 setContent 仍可用。
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
  private var canvas by mutableIntStateOf(Color.parseColor("#13100E"))
  private var bundleLoading by mutableStateOf(false)
  private var edgesPinned = false
  private var appliedLight: Boolean? = null
  private var appliedBackdrop: Int? = null
  private var splashHeld = true
  private var loadingWanted = false
  private val loadingHandler = Handler(Looper.getMainLooper())
  private val revealLoading = Runnable {
    if (loadingWanted) {
      bundleLoading = true
      Log.i(TAG, "bundle loading visible")
    }
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  private val mainComponentName: String = "Zijinhua"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */

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
    PluginCenter.onBundleLoading = { loading ->
      if (splashHeld) {
        if (!loading) splashHeld = false
      } else if (loading) {
        loadingWanted = true
        loadingHandler.removeCallbacks(revealLoading)
        loadingHandler.postDelayed(revealLoading, LOADING_REVEAL_DELAY_MS)
      } else {
        loadingWanted = false
        loadingHandler.removeCallbacks(revealLoading)
        bundleLoading = false
      }
    }
    ShellAi.restore(this)
    ShellAi.onSurfaceChanged = { runOnUiThread { applySystemBars() } }
    applySystemBars()
    ShellCapabilities.attach(this)
    onBackPressedDispatcher.addCallback(this, BackCallback())
    setContent {
      MaterialTheme {
        SideEffect { applySystemBars() }
        Surface(modifier = Modifier.fillMaxSize(), color = ComposeColor(canvas)) {
          ShellContent(reactHost, mainComponentName, ::attachSurface, bundleLoading, canvas)
        }
      }
    }
    Log.i(TAG, "activity created")
  }

  @Deprecated("Activity result API is used by React Native libraries")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    reactHost.onActivityResult(this, requestCode, resultCode, data)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
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

  private fun canvasColor(): Int {
    return if (ShellAi.lightSurface) Color.parseColor("#FFF9ED") else Color.parseColor("#19152C")
  }

  private fun applySystemBars() {
    val light = ShellAi.lightSurface
    val backdrop = canvasColor()
    if (canvas != backdrop) canvas = backdrop
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

@Composable
private fun ShellContent(
  reactHost: ReactHost,
  componentName: String,
  onSurface: (ReactSurface) -> Unit,
  loading: Boolean,
  canvas: Int,
) {
  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      // React's KeyboardProvider owns IME avoidance. Keep its root geometry fixed.
      modifier = Modifier.fillMaxSize(),
      factory = { context ->
        val created = reactHost.createSurface(context, componentName, null)
        onSurface(created)
        created.start()
        val view = created.view
          ?: throw IllegalStateException("ReactSurface has no view")
        view.setBackgroundColor(canvas)
        Log.i("MainActivity", "react surface started component=$componentName")
        view
      },
      update = { view -> view.setBackgroundColor(canvas) },
    )
    if (loading) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .background(ComposeColor(canvas)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator(color = ComposeColor(0xFF8B5CF6))
        Text(
          text = "正在加载应用",
          color = if (ShellAi.lightSurface) ComposeColor(0xFF0B111E) else ComposeColor(0xFFF1F5F9),
          fontSize = 15.sp,
          modifier = Modifier.padding(top = 16.dp),
        )
      }
    }
  }
}
