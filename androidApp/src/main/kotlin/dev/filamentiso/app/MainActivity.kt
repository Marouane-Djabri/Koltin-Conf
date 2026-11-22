package dev.filamentiso.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.filamentiso.App
import dev.filamentiso.FileHouseStore
import dev.filamentiso.loadAssetCatalog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = FileHouseStore(this)
        val extraCatalog = loadAssetCatalog(this)
        setContent { App(store = store, extraCatalog = extraCatalog) }
    }
}
