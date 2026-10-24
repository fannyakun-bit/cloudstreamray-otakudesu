
package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TestPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ExampleProvider())
    }
}
