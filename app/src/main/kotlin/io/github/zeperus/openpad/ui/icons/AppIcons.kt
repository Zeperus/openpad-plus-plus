/*
 * The vector data in this file is copied from the Material Icons of the Android Open Source Project
 * (androidx.compose.material:material-icons-extended 1.7.8), Copyright The Android Open Source Project,
 * licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package io.github.zeperus.openpad.ui.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType.Companion.EvenOdd
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The few Material icons the app draws beyond those in `material-icons-core`. Only these are copied (the full
 * `material-icons-extended` library is tens of megabytes); each is built on first use.
 */
internal object AppIcons {
    val Undo: ImageVector by lazy {
        materialIcon(name = "AutoMirrored.Filled.Undo", autoMirror = true) {
            materialPath {
                moveTo(12.5f, 8.0f)
                curveToRelative(-2.65f, 0.0f, -5.05f, 0.99f, -6.9f, 2.6f)
                lineTo(2.0f, 7.0f)
                verticalLineToRelative(9.0f)
                horizontalLineToRelative(9.0f)
                lineToRelative(-3.62f, -3.62f)
                curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
                curveToRelative(3.54f, 0.0f, 6.55f, 2.31f, 7.6f, 5.5f)
                lineToRelative(2.37f, -0.78f)
                curveTo(21.08f, 11.03f, 17.15f, 8.0f, 12.5f, 8.0f)
                close()
            }
        }
    }

    val Redo: ImageVector by lazy {
        materialIcon(name = "AutoMirrored.Filled.Redo", autoMirror = true) {
            materialPath {
                moveTo(18.4f, 10.6f)
                curveTo(16.55f, 8.99f, 14.15f, 8.0f, 11.5f, 8.0f)
                curveToRelative(-4.65f, 0.0f, -8.58f, 3.03f, -9.96f, 7.22f)
                lineTo(3.9f, 16.0f)
                curveToRelative(1.05f, -3.19f, 4.05f, -5.5f, 7.6f, -5.5f)
                curveToRelative(1.95f, 0.0f, 3.73f, 0.72f, 5.12f, 1.88f)
                lineTo(13.0f, 16.0f)
                horizontalLineToRelative(9.0f)
                verticalLineTo(7.0f)
                lineToRelative(-3.6f, 3.6f)
                close()
            }
        }
    }

    val FormatListBulleted: ImageVector by lazy {
        materialIcon(name = "AutoMirrored.Filled.FormatListBulleted",
            autoMirror = true) {
            materialPath {
                moveTo(4.0f, 10.5f)
                curveToRelative(-0.83f, 0.0f, -1.5f, 0.67f, -1.5f, 1.5f)
                reflectiveCurveToRelative(0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                reflectiveCurveToRelative(-0.67f, -1.5f, -1.5f, -1.5f)
                close()
                moveTo(4.0f, 4.5f)
                curveToRelative(-0.83f, 0.0f, -1.5f, 0.67f, -1.5f, 1.5f)
                reflectiveCurveTo(3.17f, 7.5f, 4.0f, 7.5f)
                reflectiveCurveTo(5.5f, 6.83f, 5.5f, 6.0f)
                reflectiveCurveTo(4.83f, 4.5f, 4.0f, 4.5f)
                close()
                moveTo(4.0f, 16.5f)
                curveToRelative(-0.83f, 0.0f, -1.5f, 0.68f, -1.5f, 1.5f)
                reflectiveCurveToRelative(0.68f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.68f, 1.5f, -1.5f)
                reflectiveCurveToRelative(-0.67f, -1.5f, -1.5f, -1.5f)
                close()
                moveTo(7.0f, 19.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(7.0f, 17.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(7.0f, 13.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(7.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(7.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(14.0f)
                lineTo(21.0f, 5.0f)
                lineTo(7.0f, 5.0f)
                close()
            }
        }
    }

    val FormatIndentDecrease: ImageVector by lazy {
        materialIcon(name = "AutoMirrored.Filled.FormatIndentDecrease",
            autoMirror = true) {
            materialPath {
                moveTo(11.0f, 17.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 15.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 12.0f)
                lineToRelative(4.0f, 4.0f)
                lineTo(7.0f, 8.0f)
                lineToRelative(-4.0f, 4.0f)
                close()
                moveTo(3.0f, 21.0f)
                horizontalLineToRelative(18.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 19.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(18.0f)
                lineTo(21.0f, 3.0f)
                lineTo(3.0f, 3.0f)
                close()
                moveTo(11.0f, 9.0f)
                horizontalLineToRelative(10.0f)
                lineTo(21.0f, 7.0f)
                lineTo(11.0f, 7.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(11.0f, 13.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
    }

    val FormatIndentIncrease: ImageVector by lazy {
        materialIcon(name = "AutoMirrored.Filled.FormatIndentIncrease",
            autoMirror = true) {
            materialPath {
                moveTo(3.0f, 21.0f)
                horizontalLineToRelative(18.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 19.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 8.0f)
                verticalLineToRelative(8.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                close()
                moveTo(11.0f, 17.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 15.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(18.0f)
                lineTo(21.0f, 3.0f)
                lineTo(3.0f, 3.0f)
                close()
                moveTo(11.0f, 9.0f)
                horizontalLineToRelative(10.0f)
                lineTo(21.0f, 7.0f)
                lineTo(11.0f, 7.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(11.0f, 13.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
    }

    val OpenInNew: ImageVector by lazy {
        materialIcon(name = "AutoMirrored.Filled.OpenInNew", autoMirror = true) {
            materialPath {
                moveTo(19.0f, 19.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(5.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(7.0f)
                close()
                moveTo(14.0f, 3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.59f)
                lineToRelative(-9.83f, 9.83f)
                lineToRelative(1.41f, 1.41f)
                lineTo(19.0f, 6.41f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(-7.0f)
                close()
            }
        }
    }

    val FormatBold: ImageVector by lazy {
        materialIcon(name = "Filled.FormatBold") {
            materialPath {
                moveTo(15.6f, 10.79f)
                curveToRelative(0.97f, -0.67f, 1.65f, -1.77f, 1.65f, -2.79f)
                curveToRelative(0.0f, -2.26f, -1.75f, -4.0f, -4.0f, -4.0f)
                lineTo(7.0f, 4.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(7.04f)
                curveToRelative(2.09f, 0.0f, 3.71f, -1.7f, 3.71f, -3.79f)
                curveToRelative(0.0f, -1.52f, -0.86f, -2.82f, -2.15f, -3.42f)
                close()
                moveTo(10.0f, 6.5f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-3.0f)
                close()
                moveTo(13.5f, 15.5f)
                lineTo(10.0f, 15.5f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(3.5f)
                curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
                close()
            }
        }
    }

    val FormatItalic: ImageVector by lazy {
        materialIcon(name = "Filled.FormatItalic") {
            materialPath {
                moveTo(10.0f, 4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.21f)
                lineToRelative(-3.42f, 8.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(-2.21f)
                lineToRelative(3.42f, -8.0f)
                horizontalLineTo(18.0f)
                verticalLineTo(4.0f)
                close()
            }
        }
    }

    val StrikethroughS: ImageVector by lazy {
        materialIcon(name = "Filled.StrikethroughS") {
            materialPath {
                moveTo(6.85f, 7.08f)
                curveTo(6.85f, 4.37f, 9.45f, 3.0f, 12.24f, 3.0f)
                curveToRelative(1.64f, 0.0f, 3.0f, 0.49f, 3.9f, 1.28f)
                curveToRelative(0.77f, 0.65f, 1.46f, 1.73f, 1.46f, 3.24f)
                horizontalLineToRelative(-3.01f)
                curveToRelative(0.0f, -0.31f, -0.05f, -0.59f, -0.15f, -0.85f)
                curveToRelative(-0.29f, -0.86f, -1.2f, -1.28f, -2.25f, -1.28f)
                curveToRelative(-1.86f, 0.0f, -2.34f, 1.02f, -2.34f, 1.7f)
                curveToRelative(0.0f, 0.48f, 0.25f, 0.88f, 0.74f, 1.21f)
                curveTo(10.97f, 8.55f, 11.36f, 8.78f, 12.0f, 9.0f)
                horizontalLineTo(7.39f)
                curveTo(7.18f, 8.66f, 6.85f, 8.11f, 6.85f, 7.08f)
                close()
                moveTo(21.0f, 12.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(9.62f)
                curveToRelative(1.15f, 0.45f, 1.96f, 0.75f, 1.96f, 1.97f)
                curveToRelative(0.0f, 1.0f, -0.81f, 1.67f, -2.28f, 1.67f)
                curveToRelative(-1.54f, 0.0f, -2.93f, -0.54f, -2.93f, -2.51f)
                horizontalLineTo(6.4f)
                curveToRelative(0.0f, 0.55f, 0.08f, 1.13f, 0.24f, 1.58f)
                curveToRelative(0.81f, 2.29f, 3.29f, 3.3f, 5.67f, 3.3f)
                curveToRelative(2.27f, 0.0f, 5.3f, -0.89f, 5.3f, -4.05f)
                curveToRelative(0.0f, -0.3f, -0.01f, -1.16f, -0.48f, -1.94f)
                horizontalLineTo(21.0f)
                verticalLineTo(12.0f)
                close()
            }
        }
    }

    val Code: ImageVector by lazy {
        materialIcon(name = "Filled.Code") {
            materialPath {
                moveTo(9.4f, 16.6f)
                lineTo(4.8f, 12.0f)
                lineToRelative(4.6f, -4.6f)
                lineTo(8.0f, 6.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(1.4f, -1.4f)
                close()
                moveTo(14.6f, 16.6f)
                lineToRelative(4.6f, -4.6f)
                lineToRelative(-4.6f, -4.6f)
                lineTo(16.0f, 6.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(-1.4f, -1.4f)
                close()
            }
        }
    }

    val Link: ImageVector by lazy {
        materialIcon(name = "Filled.Link") {
            materialPath {
                moveTo(3.9f, 12.0f)
                curveToRelative(0.0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                horizontalLineToRelative(4.0f)
                lineTo(11.0f, 7.0f)
                lineTo(7.0f, 7.0f)
                curveToRelative(-2.76f, 0.0f, -5.0f, 2.24f, -5.0f, 5.0f)
                reflectiveCurveToRelative(2.24f, 5.0f, 5.0f, 5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-1.9f)
                lineTo(7.0f, 15.1f)
                curveToRelative(-1.71f, 0.0f, -3.1f, -1.39f, -3.1f, -3.1f)
                close()
                moveTo(8.0f, 13.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-2.0f)
                lineTo(8.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(17.0f, 7.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(1.9f)
                horizontalLineToRelative(4.0f)
                curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
                reflectiveCurveToRelative(-1.39f, 3.1f, -3.1f, 3.1f)
                horizontalLineToRelative(-4.0f)
                lineTo(13.0f, 17.0f)
                horizontalLineToRelative(4.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                reflectiveCurveToRelative(-2.24f, -5.0f, -5.0f, -5.0f)
                close()
            }
        }
    }

    val FormatListNumbered: ImageVector by lazy {
        materialIcon(name = "Filled.FormatListNumbered") {
            materialPath {
                moveTo(2.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(0.5f)
                lineTo(3.0f, 17.5f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(0.5f)
                lineTo(2.0f, 19.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-4.0f)
                lineTo(2.0f, 16.0f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(3.0f, 8.0f)
                horizontalLineToRelative(1.0f)
                lineTo(4.0f, 4.0f)
                lineTo(2.0f, 4.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(3.0f)
                close()
                moveTo(2.0f, 11.0f)
                horizontalLineToRelative(1.8f)
                lineTo(2.0f, 13.1f)
                verticalLineToRelative(0.9f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-1.0f)
                lineTo(3.2f, 13.0f)
                lineTo(5.0f, 10.9f)
                lineTo(5.0f, 10.0f)
                lineTo(2.0f, 10.0f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(7.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(14.0f)
                lineTo(21.0f, 5.0f)
                lineTo(7.0f, 5.0f)
                close()
                moveTo(7.0f, 19.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(7.0f, 17.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(7.0f, 13.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(7.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
    }

    val CheckBox: ImageVector by lazy {
        materialIcon(name = "Filled.CheckBox") {
            materialPath {
                moveTo(19.0f, 3.0f)
                lineTo(5.0f, 3.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.11f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.89f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(10.0f, 17.0f)
                lineToRelative(-5.0f, -5.0f)
                lineToRelative(1.41f, -1.41f)
                lineTo(10.0f, 14.17f)
                lineToRelative(7.59f, -7.59f)
                lineTo(19.0f, 8.0f)
                lineToRelative(-9.0f, 9.0f)
                close()
            }
        }
    }

    val HorizontalRule: ImageVector by lazy {
        materialIcon(name = "Filled.HorizontalRule") {
            materialPath(pathFillType = EvenOdd) {
                moveTo(4.0f, 11.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-16.0f)
                close()
            }
        }
    }

    val FormatQuote: ImageVector by lazy {
        materialIcon(name = "Filled.FormatQuote") {
            materialPath {
                moveTo(6.0f, 17.0f)
                horizontalLineToRelative(3.0f)
                lineToRelative(2.0f, -4.0f)
                lineTo(11.0f, 7.0f)
                lineTo(5.0f, 7.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(3.0f)
                close()
                moveTo(14.0f, 17.0f)
                horizontalLineToRelative(3.0f)
                lineToRelative(2.0f, -4.0f)
                lineTo(19.0f, 7.0f)
                horizontalLineToRelative(-6.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(3.0f)
                close()
            }
        }
    }

    val Image: ImageVector by lazy {
        materialIcon(name = "Filled.Image") {
            materialPath {
                moveTo(21.0f, 19.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineTo(5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                close()
                moveTo(8.5f, 13.5f)
                lineToRelative(2.5f, 3.01f)
                lineTo(14.5f, 12.0f)
                lineToRelative(4.5f, 6.0f)
                horizontalLineTo(5.0f)
                lineToRelative(3.5f, -4.5f)
                close()
            }
        }
    }

    val Remove: ImageVector by lazy {
        materialIcon(name = "Filled.Remove") {
            materialPath {
                moveTo(19.0f, 13.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
    }

    val ExpandMore: ImageVector by lazy {
        materialIcon(name = "Filled.ExpandMore") {
            materialPath {
                moveTo(16.59f, 8.59f)
                lineTo(12.0f, 13.17f)
                lineTo(7.41f, 8.59f)
                lineTo(6.0f, 10.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(6.0f, -6.0f)
                close()
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        materialIcon(name = "Filled.ChevronRight") {
            materialPath {
                moveTo(10.0f, 6.0f)
                lineTo(8.59f, 7.41f)
                lineTo(13.17f, 12.0f)
                lineToRelative(-4.58f, 4.59f)
                lineTo(10.0f, 18.0f)
                lineToRelative(6.0f, -6.0f)
                close()
            }
        }
    }
}
