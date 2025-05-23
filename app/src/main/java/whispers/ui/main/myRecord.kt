package whispers.ui.main
import kotlinx.serialization.Serializable

@Serializable
data class myRecord(
    var logs: String,
    val absolutePath: String
)