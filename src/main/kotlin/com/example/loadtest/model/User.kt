package com.example.loadtest.model

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "t_users")
class User(var username: String, var password: String, var email: String) {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    var id: Long? = null
    
    var isTest: Boolean = false
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as User

        if (isTest != other.isTest) return false
        if (username != other.username) return false
        if (password != other.password) return false
        if (email != other.email) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isTest.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + email.hashCode()
        return result
    }

    override fun toString(): String {
        return "User(username='$username', password='$password', email='$email', id=$id, isTest=$isTest)"
    }

}