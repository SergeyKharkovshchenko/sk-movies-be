package com.pantopix.ticket.repositories;

import com.pantopix.ticket.entities.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

@Repository
public interface CategoryRepo extends JpaRepository<Category, Long> {
    Category findByName(String categoryName);
}