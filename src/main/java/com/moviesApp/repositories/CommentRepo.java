package com.moviesApp.repositories;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.moviesApp.entities.Comment;

import java.util.List;


@Repository
public interface CommentRepo extends JpaRepository<Comment, Long> {
    List<Comment> findByTicketId(Long ticketId);
}
