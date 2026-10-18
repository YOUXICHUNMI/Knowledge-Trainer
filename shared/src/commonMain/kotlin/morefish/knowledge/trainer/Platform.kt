package morefish.knowledge.trainer

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform