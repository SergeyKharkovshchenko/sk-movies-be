
package com.pantopix.ticket.service;
import com.pantopix.ticket.entities.Ticket;
import com.pantopix.ticket.repositories.TicketDeo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TicketService {

    @Autowired
    private TicketDeo ticketDeo;


    public Ticket createNewTicket(Ticket ticket) {
        Ticket newTicket = new Ticket();
        newTicket.setProblem(ticket.getProblem());
        newTicket.setCreatedBy(ticket.getCreatedBy());
        newTicket.setDesc(ticket.getDesc());
        newTicket.setStatus(ticket.getStatus());
        newTicket.setPriority(ticket.getPriority());
        newTicket.setAssignedTo(ticket.getAssignedTo());
        return ticketDeo.save(newTicket);
    }

    public Ticket updateExistingTicket(Ticket request) {
            Optional<Ticket> existingProduct  = ticketDeo.findById(request.getId());
        if (existingProduct .isPresent()) {
        Ticket ticket = existingProduct.get();
        ticket.setProblem(request.getProblem());
        ticket.setCreatedBy(request.getCreatedBy());
        ticket.setDesc(request.getDesc());
        ticket.setStatus(request.getStatus());
        ticket.setPriority(request.getPriority());
        ticket.setAssignedTo(request.getAssignedTo());
        return ticketDeo.save(ticket);
}
        return null;
}

    public Iterable<Ticket> getAllTickets() {
        return ticketDeo.findAll();
    }

        public  Ticket getTicket(Ticket getRequests) {
            Optional<Ticket> getTicketById = ticketDeo.findById(getRequests.getId());
            if(getTicketById.isPresent()) {
                Ticket ticket = getTicketById.get();
                return ticket;
            }
            return null;
}


    public boolean deleteTicket(Ticket getRequests) {
        Optional<Ticket> deleteTicketById = ticketDeo.findById(getRequests.getId());
        if(deleteTicketById.isPresent()) {
             ticketDeo.deleteById(getRequests.getId());
             return true;
    }
       else {
           return false;
        }
}


            public void deleteAllTickets() {
                ticketDeo.deleteAll();
            }
        }





