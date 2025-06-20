package com.vulnerable.webapp.repository;

import com.vulnerable.webapp.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    // Vulnerable SQL injection method - concatenating user input directly
    @Query(value = "SELECT * FROM users WHERE username = '" + ":username" + "' AND password = '" + ":password" + "'", nativeQuery = true)
    List<User> findByUsernameAndPasswordVulnerable(@Param("username") String username, @Param("password") String password);
    
    // Safe method for comparison
    User findByUsernameAndPassword(String username, String password);
    
    // Another vulnerable method for search
    @Query(value = "SELECT * FROM users WHERE username LIKE '%" + ":searchTerm" + "%' OR email LIKE '%" + ":searchTerm" + "%'", nativeQuery = true)
    List<User> searchUsersVulnerable(@Param("searchTerm") String searchTerm);
} 