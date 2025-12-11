package com.moviesApp.controllers;

import jakarta.persistence.EntityNotFoundException;

import org.hibernate.Remove;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import com.moviesApp.common.TicketDto;
import com.moviesApp.entities.Category;
import com.moviesApp.entities.Comment;
import com.moviesApp.entities.Summary;
import com.moviesApp.entities.Ticket;
import com.moviesApp.entities.User;
import com.moviesApp.model.TicketStatus;
import com.moviesApp.repositories.TicketDeo;
import com.moviesApp.service.EmailService;
import com.moviesApp.service.TicketService;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class  TicketController {

    @Autowired
    private TicketDeo ticketDeo;

    @Autowired
    private EmailService emailService;

    @Autowired
    private TicketService ticketService;

    @ControllerAdvice
    public class GlobalExceptionHandler {

        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<Object> handleInvalidEnum(HttpMessageNotReadableException ex) {
            String message = ex.getMessage();
            String field = "Unknown field";
            String enumOptions = "Unknown field";

            if (message.toLowerCase().contains("priority")) {
                field = "priority";
                enumOptions = "LOW, HIGH, MEDIUM";
            } else if (message.toLowerCase().contains("status")) {
                field = "status";
                enumOptions = "OPEN, IN_PROGRESS, DONE, CLOSED";
            }

            Map<String, String> response = new HashMap<>();
            response.put("error", "Invalid value for enum field: " + field + ". Accepted values are: " + enumOptions);
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }

    }

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

    @GetMapping("/dashboard/summary")
    public ResponseEntity<Summary> getSummary() {
        Summary ticketsPerPriority = ticketService.getticketsPerPriority();
        return ResponseEntity.ok(ticketsPerPriority);
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