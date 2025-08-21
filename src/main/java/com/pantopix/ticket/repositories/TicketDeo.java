package com.pantopix.ticket.repositories;

import com.pantopix.ticket.entities.Ticket;
import com.pantopix.ticket.model.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TicketDeo extends JpaRepository<Ticket, Long> {
    List<Ticket> findAllByStatusAndProblem(TicketStatus status, String keyword);
}
