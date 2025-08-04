package whispers.ui.main
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@InternalSerializationApi @Serializable
data class myRecord(
    var logs: String,
    val absolutePath: String
)