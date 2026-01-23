package com.example.loadtest.controller

import com.example.loadtest.model.User
import com.example.loadtest.service.UserService
import com.example.loadtest.traffic.TrafficContextManager
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users")
class UserController(private val userService: UserService, private val trafficContextManager: TrafficContextManager) {

    @GetMapping
    fun getUsers(): ResponseEntity<List<User>> {
        val users = userService.getUsers()
        return ResponseEntity.ok(users)
    }

    @GetMapping("/{id}")
    fun getUserById(@PathVariable id: Long): ResponseEntity<User> {
        val user = userService.getUserById(id)
        return if (user == null) {
            ResponseEntity.notFound().build()
        } else {
            ResponseEntity.ok(user)
        }
    }

    @PostMapping
    fun createUser(
        @RequestBody userRequest: UserRequest
    ): ResponseEntity<User> {
        val user = userService.createUser(userRequest.username, userRequest.password, userRequest.email, trafficContextManager.isTestTraffic())
        return ResponseEntity.status(201).body(user)
    }

    @PutMapping("/{id}")
    fun updateUser(@PathVariable id: Long, @RequestBody request: UserRequest): ResponseEntity<User> {
        val user = userService.updateUser(id, request.username, request.password, request.email)
        return if (user == null) {
            ResponseEntity.notFound().build()
        } else {
            ResponseEntity.ok(user)
        }
    }
}

data class UserRequest(
    val username: String,
    val password: String,
    val email: String
)