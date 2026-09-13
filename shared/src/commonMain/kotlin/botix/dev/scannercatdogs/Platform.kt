package botix.dev.scannercatdogs

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform