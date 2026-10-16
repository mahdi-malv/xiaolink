package wtf.mahdi.glassesconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import wtf.mahdi.glassesconnect.ui.XiaoApp
import wtf.mahdi.glassesconnect.ui.theme.XiaoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = GlassesController.get(application)
        setContent { XiaoTheme { XiaoApp(controller) } }
    }
}
