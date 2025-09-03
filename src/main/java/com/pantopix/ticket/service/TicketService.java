

package com.pantopix.ticket.service;
import com.pantopix.ticket.entities.Comment;
import com.pantopix.ticket.common.CategoryDto;
import com.pantopix.ticket.common.TicketDto;
import com.pantopix.ticket.entities.Category;

import com.pantopix.ticket.entities.Ticket;
import com.pantopix.ticket.model.TicketStatus;
import com.pantopix.ticket.repositories.CommentRepo;
import com.pantopix.ticket.repositories.CategoryRepo;
import com.pantopix.ticket.entities.User;
import com.pantopix.ticket.repositories.TicketDeo;
import jakarta.persistence.EntityNotFoundException;
import com.pantopix.ticket.repositories.UserDeo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.HashSet;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class TicketService {

    @Autowired
    private TicketDeo ticketDeo;

    @Autowired
    private UserDeo userDeo;
    @Autowired
    private CommentRepo commentRepo;

//    @Autowired
//    private CategoryRepo categoryRepo;

    @Autowired
    private CategoryRepo categoryRepo;


    public Ticket createNewTicket(TicketDto ticket) {
        Ticket newTicket = new Ticket();
        newTicket.setProblem(ticket.getProblem());
        newTicket.setCreatedBy(ticket.getCreatedBy());
        newTicket.setDesc(ticket.getDesc());
        newTicket.setStatus(ticket.getStatus());
        newTicket.setPriority(ticket.getPriority());
        newTicket.setAssignedTo(ticket.getAssignedTo());

        Set<Category> validatedCategories = new HashSet<>();
        for (CategoryDto category : ticket.getCategories()) {
            Category found = categoryRepo.findByName(category.getCategoryName().name());
            validatedCategories.add(found);
            newTicket.setCategories(validatedCategories);
        }
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
        ticket.setCategories(ticket.getCategories());
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


    public void deleteAllCategories() {
        categoryRepo.deleteAll();
    }


    public List<Ticket> searchTickets(TicketStatus status, String keyword) {
        return ticketDeo.findAllByStatusAndProblem(status, keyword);

    }

    public Comment createComment(Long ticketId, Comment comment) {
        Comment newComment = new Comment();
        Optional<Ticket> existingTicket = ticketDeo.findById(ticketId);

        if (existingTicket.isPresent()) {
            newComment.setContent(comment.getContent());
            newComment.setCreatedAt(new Date());
            newComment.setTicket(existingTicket.get());
        }
        return commentRepo.save(newComment);
    }

    public List<Comment> getCommentById(Long ticketId) {
        return commentRepo.findByTicketId(ticketId);
    }

    public Comment updateExistingComment(Long commentId, Comment updatedComment) {
        Optional<Comment> existingComment = commentRepo.findById(commentId);

        if (existingComment.isPresent()) {
            Comment comment = existingComment.get();
            comment.setContent(updatedComment.getContent());
            return commentRepo.save(comment);
        } else {
            throw new EntityNotFoundException("Comment not found with ID: " + commentId);
        }
    }

    public boolean deleteComment(Long commentId) {
        Optional<Comment> deleteComment = commentRepo.findById(commentId);
        if (deleteComment.isPresent()) {
            commentRepo.deleteById(commentId);
            return true;
        } else {
            return false;
        }

    }
}




