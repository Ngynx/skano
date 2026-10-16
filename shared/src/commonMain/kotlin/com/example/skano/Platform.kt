package com.example.skano

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform