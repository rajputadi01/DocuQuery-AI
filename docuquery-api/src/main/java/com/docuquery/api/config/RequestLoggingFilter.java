package com.docuquery.api.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RequestLoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        
        long startTime = System.currentTimeMillis();
        
        // Continue the request
        chain.doFilter(request, response);
        
        long duration = System.currentTimeMillis() - startTime;
        
        // Log the metrics (This prints to Render's log stream in production)
        System.out.printf("[DocuQuery-Ops] Method: %s | URI: %s | Status: %d | Latency: %d ms%n",
                req.getMethod(), req.getRequestURI(), res.getStatus(), duration);
    }
}