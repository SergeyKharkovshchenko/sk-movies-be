package com.moviesApp.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.moviesApp.entities.Category;
import com.moviesApp.entities.Ticket;
import com.moviesApp.model.TicketStatus;

import java.util.List;
import java.util.Set;



@Repository
public interface TicketDeo extends JpaRepository<Ticket, Long> {
    List<Ticket> findAllByStatusAndProblem(TicketStatus status, String keyword);
    List<Ticket> findByCategories(Set<Category> categoryName);
}
