package com.pantopix.ticket.entities;


import jakarta.persistence.*;

import javax.validation.constraints.NotNull;
import java.util.Date;

@Entity
@Table(name = "comment")


public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @NotNull(message = "Comment ID must not be null")
    public Long commentId;
    private String content;
    private Date createdAt;

    @ManyToOne
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    public Long getCommentId() {
        return commentId;
    }

    public void setCommentId(Long commentId) {
        this.commentId = commentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public void setTicket(Ticket ticket) {
        this.ticket = ticket;
    }
}
