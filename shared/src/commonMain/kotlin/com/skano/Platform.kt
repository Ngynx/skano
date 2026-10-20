package com.skano

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform