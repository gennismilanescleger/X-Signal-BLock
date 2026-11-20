// Herramientas — hub (superprompt §6, Fase 2; Fase 6 completes it).
//
// The four main tabs are Inicio / Explorar / Antenas / Herramientas. The
// old Bands, Tools and Settings tabs did not disappear: they live here as
// sub-destinations, so every BunnyLock function stays reachable and nothing
// is duplicated. Fase 5 adds Orientación (map + compass) as a real
// destination; Fase 6 adds Cámara, Datos and Diagnóstico the same way —
// never as dead entries.
//
// Groups (superprompt §6.1):
//   - Orientación y campo: Orientación (map + compass), Cámara.
//   - Registros: Datos (measurements, points, photos, observed cells,
//     export/import), Diagnóstico (the one full report).
//   - Sistema: Bandas, Herramientas del módem (the old Tools screen:
//     radio, consoles, log), Ajustes. The app information (version,
//     GPLv3, attribution) lives in Ajustes > About, not as a second
//     About destination — one About, in the place Settings already had.
//
// The destination survives tab switches and process death: BunnyApp keeps
// the selected destination name in a rememberSaveable. Tapping the
// Herramientas tab while already on it goes back to the hub; the system
// Back button does the same from any sub-destination.

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.ui.design.SxScreenHeader
import dev.ririxi.bunnylock.ui.design.SxSectionHeader

/** Sub-destinations inside the Herramientas tab. */
enum class ToolsDestination(
    @StringRes val label: Int,
    @StringRes val hint: Int,
    val icon: ImageVector,
) {
    ORIENTATION(R.string.orient_title, R.string.hub_orientation_hint, Icons.Rounded.Place),
    CAMERA(R.string.camera_title, R.string.hub_camera_hint, BunnyIcons.Camera),
    DATA(R.string.data_title, R.string.hub_data_hint, Icons.Rounded.List),
    DIAGNOSTICS(R.string.diag_title, R.string.hub_diagnostics_hint, Icons.Rounded.Info),
    BANDS(R.string.shell_tab_bands, R.string.hub_bands_hint, BunnyIcons.Bands),
    TOOLS(R.string.hub_tools_modem, R.string.hub_tools_hint, Icons.Rounded.Build),
    SETTINGS(R.string.shell_tab_settings, R.string.hub_settings_hint, Icons.Rounded.Settings),
}

@Composable
fun ToolsHub(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    destination: ToolsDestination?,
    onDestination: (ToolsDestination?) -> Unit,
    goToAntennas: () -> Unit,
) {
    BackHandler(enabled = destination !== null) { onDestination(null) }
    when (destination) {
        null -> ToolsHubHome(contentPadding, onDestination)
        ToolsDestination.ORIENTATION ->
            OrientationScreen(
                state, vm, contentPadding, goToAntennas,
                onOpenCamera = { onDestination(ToolsDestination.CAMERA) },
            )
        ToolsDestination.CAMERA -> CameraScreen(state, vm, contentPadding, onClose = { onDestination(null) })
        ToolsDestination.DATA -> DataScreen(vm, contentPadding)
        ToolsDestination.DIAGNOSTICS -> DiagnosticsScreen(state, vm, contentPadding)
        ToolsDestination.BANDS -> BandsScreen(state, vm, contentPadding)
        ToolsDestination.TOOLS -> ToolsScreen(state, vm, contentPadding)
        ToolsDestination.SETTINGS -> SettingsScreen(state, vm, contentPadding)
    }
}

@Composable
private fun ToolsHubHome(
    contentPadding: PaddingValues,
    onDestination: (ToolsDestination) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SxScreenHeader(stringResource(R.string.shell_tab_herramientas)) }

        item {
            SxSectionHeader(stringResource(R.string.hub_group_field))
            Segmented {
                listOf(ToolsDestination.ORIENTATION, ToolsDestination.CAMERA).forEach { dest ->
                    item { shapes ->
                        ActionItem(
                            shapes,
                            stringResource(dest.label),
                            stringResource(dest.hint),
                            dest.icon,
                            enabled = true,
                            onClick = { onDestination(dest) },
                        )
                    }
                }
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.hub_group_records))
            Segmented {
                listOf(ToolsDestination.DATA, ToolsDestination.DIAGNOSTICS).forEach { dest ->
                    item { shapes ->
                        ActionItem(
                            shapes,
                            stringResource(dest.label),
                            stringResource(dest.hint),
                            dest.icon,
                            enabled = true,
                            onClick = { onDestination(dest) },
                        )
                    }
                }
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.hub_group_system))
            Segmented {
                listOf(ToolsDestination.BANDS, ToolsDestination.TOOLS, ToolsDestination.SETTINGS).forEach { dest ->
                    item { shapes ->
                        ActionItem(
                            shapes,
                            stringResource(dest.label),
                            stringResource(dest.hint),
                            dest.icon,
                            enabled = true,
                            onClick = { onDestination(dest) },
                        )
                    }
                }
            }
        }
    }
}
