package app.aaps.compose.dashboard

import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import app.aaps.R

/**
 * Embeds [app.aaps.plugins.main.general.dashboard.DashboardFragment] inside Compose
 * so the AIMI hybrid dashboard keeps the Compose shell (search, Manage, QuickLaunch, bottom nav).
 */
@Composable
fun DashboardOverviewHost(
    paddingValues: PaddingValues,
    fabBottomOffset: Dp,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as AppCompatActivity
    DisposableEffect(Unit) {
        onDispose {
            if (!activity.isFinishing && !activity.supportFragmentManager.isStateSaved) {
                val f = activity.supportFragmentManager.findFragmentById(R.id.compose_dashboard_fragment_host)
                if (f != null) {
                    activity.supportFragmentManager.beginTransaction()
                        .remove(f)
                        .commitAllowingStateLoss()
                }
            }
        }
    }
    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(bottom = fabBottomOffset),
        factory = { ctx ->
            LayoutInflater.from(ctx).inflate(R.layout.compose_dashboard_fragment_host, null, false)
                as FragmentContainerView
        },
    )
}
