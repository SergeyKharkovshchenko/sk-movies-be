package com.pantopix.ticket.repositories;

import com.pantopix.ticket.entities.Ticket;
import org.springframework.context.annotation.Bean;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Properties;

@Repository
public interface TicketDeo extends CrudRepository<Ticket, Long> {

}
