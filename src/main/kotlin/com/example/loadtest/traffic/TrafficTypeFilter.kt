package com.example.loadtest.traffic

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(0)
class TrafficTypeFilter(private val trafficContextManager: TrafficContextManager) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            trafficContextManager.setTrafficContext(TrafficContext.of(request))
            filterChain.doFilter(request, response)
        } finally {
            trafficContextManager.clearTrafficContext()
        }
    }
}
