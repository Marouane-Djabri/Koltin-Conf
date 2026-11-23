package dev.filamentiso.world.platform

import io.github.vinceglb.filekit.dialogs.FileKitType

/**
 * Which files the system picker offers for a model import.
 * Web: `<input accept=".glb">`. Android: all files, because most document providers don't know
 * the `model/gltf-binary` MIME type and would hide every .glb; our validator rejects anything else.
 */
expect val glbPickerType: FileKitType
