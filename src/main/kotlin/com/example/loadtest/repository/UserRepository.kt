package com.example.loadtest.repository

import com.example.loadtest.model.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email:String) : Optional<User>
}