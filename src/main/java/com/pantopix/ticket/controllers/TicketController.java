package com.pantopix.ticket.controllers;

import com.pantopix.ticket.common.TicketDto;
import com.pantopix.ticket.entities.Category;
import com.pantopix.ticket.entities.Comment;
import com.pantopix.ticket.entities.Ticket;
import com.pantopix.ticket.entities.User;
import com.pantopix.ticket.model.TicketStatus;
import com.pantopix.ticket.repositories.TicketDeo;
import com.pantopix.ticket.service.EmailService;
import jakarta.persistence.EntityNotFoundException;

import org.hibernate.Remove;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.pantopix.ticket.service.TicketService;

@RestController
public class  TicketController {

    @Autowired
    private TicketDeo ticketDeo;

    @Autowired
    private EmailService emailService;

    @Autowired
    private TicketService ticketService;

    @PostMapping("/save")
    public ResponseEntity<Ticket> createNewTicket(@RequestBody @Validated TicketDto ticket) {
        Ticket newTicket = ticketService.createNewTicket(ticket);

        String[] senders = new String[] {
                newTicket.getCreatedBy(),
                newTicket.getAssignedTo()
        };
        emailService.sendSimpleEmail(
                senders,
                "New ticket has been created",
                "A new ticket is added to the system. ID: " + newTicket.getId() + ", " +
                        "Description: " + newTicket.getDesc() + ", " +
                        "Created By: " + newTicket.getCreatedBy());
        return ResponseEntity.ok(newTicket);
    }

    @PutMapping("/update")
    public ResponseEntity<Ticket> updateTicket(@RequestBody Ticket request) {
        Ticket updateTicket = ticketService.updateExistingTicket(request);
        String[] senders = new String[] {
                updateTicket.getCreatedBy()
        };
        if (updateTicket.getStatus().equals(TicketStatus.DONE)) {
            emailService.sendSimpleEmail(
                    senders,
                    "Ticket Completed",
                    "Ticket ID: " + updateTicket.getId() + " has been marked as Resolved.");
        }
        return ResponseEntity.ok(updateTicket);
    }

    @GetMapping("/getAlltickets")
    public ResponseEntity<Iterable<Ticket>> getAllTickets() {
        Iterable<Ticket> tickets = ticketService.getAllTickets();
        return ResponseEntity.ok(tickets);
    }

    @GetMapping("/getTicket")
    public ResponseEntity<?> getTicket(@RequestBody Ticket getRequests) {
        Ticket getTicketById = ticketService.getTicket(getRequests);
        if (getTicketById == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("The ticket with ID  does not exist.");
        }

        return ResponseEntity.ok(getTicketById);

    }

    @DeleteMapping("/deleteTicket")
    public ResponseEntity<?> deleteTicket(@RequestBody Ticket getRequests) {
        boolean deleteTicketById = ticketService.deleteTicket(getRequests);
        if (!deleteTicketById) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No ID found in database...............");
        }
        return ResponseEntity.ok(deleteTicketById);
    }

    @DeleteMapping("/deleteAll")
    public ResponseEntity<Ticket> deleteAllTickets() {
        ticketService.deleteAllTickets();
        return new ResponseEntity<>(HttpStatus.OK);
    }


@GetMapping("/search")
public ResponseEntity<List<Ticket>> searchTickets(@RequestParam TicketStatus status, @RequestParam String  keyword) {
    List<Ticket> searchByTicket =  ticketService.searchTickets(status, keyword);
    return ResponseEntity.ok(searchByTicket);
    }


    @PostMapping("/{ticketId}/comments")
    public Comment createComment(@PathVariable("ticketId") Long id, @RequestBody Comment comment) {
        return ticketService.createComment(id, comment);
    }


    @GetMapping("/{ticketId}/listComments")
    public ResponseEntity<List<Comment>> listComments(@PathVariable Long ticketId) {
        List<Comment> comments = ticketService.getCommentById(ticketId);
      return ResponseEntity.ok(comments);
    }

    @PutMapping("/updateComment")
    public ResponseEntity<?> updateExistingComment(@RequestBody Comment comment) {
        try {
        Comment result = ticketService.updateExistingComment(comment.getCommentId(), comment);
        return ResponseEntity.ok(result);
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Comment not found.");
        }
    }


    @DeleteMapping("/deleteComment/{id}")
    public ResponseEntity<?> deleteComment(@PathVariable("id") Long id) {
        boolean deletedId = ticketService.deleteComment(id);
        if(!deletedId ) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No ID found in database...............");
        }
        return ResponseEntity.ok(deletedId);
    }

    @DeleteMapping("/deleteAllCategories")
    public ResponseEntity<Category> deleteAllCategories() {
        ticketService.deleteAllCategories();
        return new ResponseEntity<>(HttpStatus.OK);
    }
}