package com.nelson.yomukai.domain.model

data class Chapter(
    val id: String,
    val mediaId: String,
    val title: String,
    val chapterNumber: Float,
    val url: String,
    val dateUploaded: String
)
