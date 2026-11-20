package cc.xconflictionx.reponotes.data

data class Note(
    val id: Long,
    val repoPath: String,
    val title: String,
    val body: String,
    val createdUtc: String,
    val updatedUtc: String
)
