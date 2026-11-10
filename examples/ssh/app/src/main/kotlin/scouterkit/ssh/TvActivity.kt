package scouterkit.ssh

import android.app.Activity
import android.os.Bundle

// The TV remote as its own screen (Settings, TV remote): the same page as the app's TV tab (TvPage).
class TvActivity : Activity() {
    private lateinit var tv: TvPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TvPage(this, page()) { finish() }
    }

    override fun onResume() {
        super.onResume()
        tv.refresh()
    }
}
