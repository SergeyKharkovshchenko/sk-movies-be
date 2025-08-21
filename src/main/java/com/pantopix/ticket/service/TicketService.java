
package com.pantopix.ticket.service;

import com.pantopix.ticket.entities.Ticket;
import com.pantopix.ticket.entities.User;
import com.pantopix.ticket.repositories.TicketDeo;
import com.pantopix.ticket.repositories.UserDeo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
public class TicketService {

    @Autowired
    private TicketDeo ticketDeo;

    @Autowired
    private UserDeo userDeo;

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
        Optional<Ticket> existingProduct = ticketDeo.findById(request.getId());
        if (existingProduct.isPresent()) {
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

    public Ticket getTicket(Ticket getRequests) {
        Optional<Ticket> getTicketById = ticketDeo.findById(getRequests.getId());
        if (getTicketById.isPresent()) {
            Ticket ticket = getTicketById.get();
            return ticket;
        }
        return null;
    }

    public boolean deleteTicket(Ticket getRequests) {
        Optional<Ticket> deleteTicketById = ticketDeo.findById(getRequests.getId());
        if (deleteTicketById.isPresent()) {
            ticketDeo.deleteById(getRequests.getId());
            return true;
        } else {
            return false;
        }
    }

    public void deleteAllTickets() {
        ticketDeo.deleteAll();
    }

    public Set<User> getAllWatchersByTicketId(Long ticketId) {
        Optional<Ticket> getTicketById = ticketDeo.findById(ticketId);
        Ticket ticket = getTicketById.get();
        return ticket.getWatchers();
    }

    public Ticket addWatcherToTicketById(Long ticketId, Long userId) {
        Optional<Ticket> getTicketById = ticketDeo.findById(ticketId);
        Optional<User> getUserById = userDeo.findById(userId);
        if (getTicketById.isPresent()) {
            Ticket ticket = getTicketById.get();
            User user = getUserById.get();

            Set<User> watchers = ticket.getWatchers();
            if (watchers == null) {
                watchers = new java.util.HashSet<>();
            }
            watchers.add(user);
            ticket.setWatchers(watchers);
            return ticketDeo.save(ticket);
        }
        return null;
    }

    public Ticket removeWatcherFromTicket(Long ticketId, Long userId) {
        Optional<Ticket> getTicketById = ticketDeo.findById(ticketId);
        Optional<User> getUserById = userDeo.findById(userId);

        if (getTicketById.isPresent() && getUserById.isPresent()) {
            Ticket ticket = getTicketById.get();
            User user = getUserById.get();

            Set<User> watchers = ticket.getWatchers();
            if (watchers != null && watchers.contains(user)) {
                watchers.remove(user);
                ticket.setWatchers(watchers);
                return ticketDeo.save(ticket);
            }
        }
        return null;
    }

}
