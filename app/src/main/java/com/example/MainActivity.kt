package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.engine.OperatingMode
import com.example.ui.CalibrateScreen
import com.example.ui.LaunchpadScreen
import com.example.ui.MainViewModel
import com.example.ui.SandboxScreen
import com.example.ui.theme.TacticianTheme
import kotlinx.coroutines.launch

private const val ROUTE_LAUNCHPAD = "launchpad"
private const val ROUTE_CALIBRATE = "calibrate"
private const val ROUTE_SANDBOX = "sandbox"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TacticianTheme(darkTheme = true) {
                TacticianAppRoot()
            }
        }
    }
}

@Composable
fun TacticianAppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val runtime by vm.overlayRuntime.collectAsStateWithLifecycle()
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val roiConfig by vm.roiConfig.collectAsStateWithLifecycle()
    val sandboxState by vm.sandbox.collectAsStateWithLifecycle()

    // Refresh permission badges whenever returning from System Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            vm.startCaptureService(result.resultCode, result.data!!)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Screen capture permission was canceled.")
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.refreshPermissions()
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar("Notification permission helps keep the capture service active.")
            }
        }
    }

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchProjectionFlow() {
        vm.refreshPermissions()
        if (!Settings.canDrawOverlays(context)) {
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissions.hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mgr.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

    fun launchEngineFlow() {
        vm.refreshPermissions()
        val currentPerms = vm.permissions.value

        if (roiConfig.operatingMode == OperatingMode.AUTOPLAY && !currentPerms.hasAccessibilityPermission) {
            scope.launch {
                snackbarHostState.showSnackbar("Enable Accessibility Autoplay first so Tactician can drag pieces.")
            }
            openAccessibilitySettings()
            return
        }

        // Prefer zero-popup Accessibility capture on Android 11-15+ whenever connected
        if (vm.tryStartAccessibilitySession()) {
            scope.launch {
                snackbarHostState.showSnackbar("Engine active via Android Accessibility — switch to Block Blast Lite!")
            }
            return
        }

        // Automatic fallback to MediaProjection (Android 8-10 or when Accessibility isn't active)
        launchProjectionFlow()
    }

    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route ?: ROUTE_LAUNCHPAD

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == ROUTE_LAUNCHPAD,
                    onClick = {
                        navController.navigate(ROUTE_LAUNCHPAD) {
                            launchSingleTop = true
                            popUpTo(ROUTE_LAUNCHPAD)
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentRoute == ROUTE_LAUNCHPAD) {
                                Icons.Rounded.RocketLaunch
                            } else {
                                Icons.Outlined.RocketLaunch
                            },
                            contentDescription = stringResource(R.string.nav_launchpad)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_launchpad)) },
                    modifier = Modifier.testTag("nav_launchpad")
                )
                NavigationBarItem(
                    selected = currentRoute == ROUTE_CALIBRATE,
                    onClick = {
                        navController.navigate(ROUTE_CALIBRATE) {
                            launchSingleTop = true
                            popUpTo(ROUTE_LAUNCHPAD)
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentRoute == ROUTE_CALIBRATE) {
                                Icons.Rounded.Tune
                            } else {
                                Icons.Outlined.Tune
                            },
                            contentDescription = stringResource(R.string.nav_calibrate)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_calibrate)) },
                    modifier = Modifier.testTag("nav_calibrate")
                )
                NavigationBarItem(
                    selected = currentRoute == ROUTE_SANDBOX,
                    onClick = {
                        navController.navigate(ROUTE_SANDBOX) {
                            launchSingleTop = true
                            popUpTo(ROUTE_LAUNCHPAD)
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentRoute == ROUTE_SANDBOX) {
                                Icons.Rounded.GridOn
                            } else {
                                Icons.Outlined.GridOn
                            },
                            contentDescription = stringResource(R.string.nav_sandbox)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_sandbox)) },
                    modifier = Modifier.testTag("nav_sandbox")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            NavHost(
                navController = navController,
                startDestination = ROUTE_LAUNCHPAD,
                modifier = Modifier.widthIn(max = 620.dp)
            ) {
                composable(ROUTE_LAUNCHPAD) {
                    LaunchpadScreen(
                        runtime = runtime,
                        permissions = permissions,
                        roiConfig = roiConfig,
                        onRequestAccessibilityPermission = ::openAccessibilitySettings,
                        onRequestOverlayPermission = ::openOverlaySettings,
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onSelectOperatingMode = vm::setOperatingMode,
                        onSelectGlobalProfile = vm::setGlobalProfile,
                        onLaunchOverlay = ::launchEngineFlow,
                        onLaunchProjectionFallback = ::launchProjectionFlow,
                        onTogglePause = vm::togglePauseCaptureService,
                        onStopOverlay = vm::stopCaptureService
                    )
                }
                composable(ROUTE_CALIBRATE) {
                    CalibrateScreen(
                        config = roiConfig,
                        isCalibratingPending = runtime.pendingGuidedCalibration,
                        calibratingStep = runtime.guidedCalibrationStep,
                        onArmGuidedCalibration = {
                            val armed = vm.armGuidedCalibration()
                            if (armed) {
                                if (runtime.status == com.example.data.ServiceStatus.STOPPED &&
                                    permissions.hasAccessibilityPermission
                                ) {
                                    vm.tryStartAccessibilitySession()
                                }
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Guided 3-Point Calibration armed — switch to Block Blast Lite!"
                                    )
                                }
                            }
                        },
                        onUpdateGridBounds = vm::updateGridBounds,
                        onUpdateTrayBounds = vm::updateTrayBounds,
                        onUpdateOpacity = vm::updateOverlayOpacity,
                        onUpdateDragLift = vm::updateDragLiftOffset,
                        onUpdateDragVerticalGain = vm::updateDragVerticalGain,
                        onUpdateDragHorizontalOffset = vm::updateDragHorizontalOffset,
                        onUpdateDragHorizontalGain = vm::updateDragHorizontalGain,
                        onResetDefaults = vm::resetRoiDefaults
                    )
                }
                composable(ROUTE_SANDBOX) {
                    SandboxScreen(
                        state = sandboxState,
                        onToggleCell = vm::toggleSandboxCell,
                        onSelectSlot = vm::selectSandboxSlot,
                        onPickPiece = vm::setSandboxSlotPiece,
                        onSolve = vm::solveSandbox,
                        onClearBoard = vm::clearSandboxBoard
                    )
                }
            }
        }
    }
}
