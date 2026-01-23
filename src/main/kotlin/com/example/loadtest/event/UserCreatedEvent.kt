package com.example.loadtest.event

data class UserCreatedEvent(
    val id: Long,
    val username: String,
    val email: String,
    val isTest: Boolean
)
