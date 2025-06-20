package com.vulnerable.webapp.controller;

import com.vulnerable.webapp.model.User;
import com.vulnerable.webapp.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/login")
    public String loginForm() {
        return "login";
    }

    // Vulnerable login endpoint with SQL injection
    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password, Model model) {
        try {
            // VULNERABLE: Direct SQL injection through repository method
            List<User> users = userRepository.findByUsernameAndPasswordVulnerable(username, password);
            
            if (!users.isEmpty()) {
                User user = users.get(0);
                model.addAttribute("user", user);
                // VULNERABLE: XSS - directly outputting user input without sanitization
                model.addAttribute("welcomeMessage", "Welcome back, " + username + "!");
                return "dashboard";
            } else {
                // VULNERABLE: XSS - reflecting user input in error message
                model.addAttribute("error", "Invalid credentials for user: " + username);
                return "login";
            }
        } catch (Exception e) {
            // VULNERABLE: Information disclosure - exposing SQL errors
            model.addAttribute("error", "Database error: " + e.getMessage());
            return "login";
        }
    }

    @GetMapping("/search")
    public String searchForm() {
        return "search";
    }

    // Vulnerable search endpoint
    @PostMapping("/search")
    public String searchUsers(@RequestParam String searchTerm, Model model) {
        try {
            // VULNERABLE: SQL injection in search
            List<User> users = userRepository.searchUsersVulnerable(searchTerm);
            model.addAttribute("users", users);
            // VULNERABLE: XSS - directly outputting search term without sanitization
            model.addAttribute("searchTerm", searchTerm);
            model.addAttribute("message", "Search results for: " + searchTerm);
        } catch (Exception e) {
            // VULNERABLE: Information disclosure
            model.addAttribute("error", "Search error: " + e.getMessage());
            model.addAttribute("searchTerm", searchTerm);
        }
        return "search";
    }

    @GetMapping("/profile")
    public String profile(@RequestParam(required = false) String message, Model model) {
        // VULNERABLE: XSS - directly reflecting URL parameter
        if (message != null) {
            model.addAttribute("message", message);
        }
        return "profile";
    }

    // Admin endpoint with vulnerable parameter handling
    @GetMapping("/admin")
    public String admin(@RequestParam(required = false) String debug, Model model) {
        // VULNERABLE: XSS and potential information disclosure
        if (debug != null) {
            model.addAttribute("debugInfo", "Debug mode enabled with parameter: " + debug);
        }
        
        List<User> allUsers = userRepository.findAll();
        model.addAttribute("users", allUsers);
        return "admin";
    }
} 