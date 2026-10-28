package morefish.knowledge.trainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import morefish.knowledge.trainer.infrastructure.storage.FileMediaStore
import morefish.knowledge.trainer.infrastructure.storage.FileTextStorage
import morefish.knowledge.trainer.presentation.App
import morefish.knowledge.trainer.presentation.AppDependencies
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Android 组合根：数据写在应用私有目录，不申请任何存储权限。
        val dir = File(filesDir, "knowledge-trainer")
        val deps = AppDependencies(
            FileTextStorage(dir),
            mediaStore = FileMediaStore(dir),
        )

        setContent {
            App(deps)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
