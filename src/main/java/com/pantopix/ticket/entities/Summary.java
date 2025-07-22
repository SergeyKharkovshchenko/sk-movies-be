package com.pantopix.ticket.entities;

import java.util.EnumMap;

import com.pantopix.ticket.model.TicketsPerPriority.Priority;

import jakarta.persistence.*;

@Entity
@Table(name = "summary")
public class Summary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long totalTickets;
    private Number openTickets;
    private Number closedTickets;
    private EnumMap<Priority, Long> ticketsPerPriority;

    public Long getTotalTickets() {
        return totalTickets;
    }

    public void setTotalTickets(Long totalTickets) {
        this.totalTickets = totalTickets;
    }

    public Number getOpenTickets() {
        return openTickets;
    }

    public void setOpenTickets(Number openTickets) {
        this.openTickets = openTickets;
    }

    public Number getClosedTickets() {
        return closedTickets;
    }

    public void setClosedTickets(Number closedTickets) {
        this.closedTickets = closedTickets;
    }

    public EnumMap<Priority, Long> getTicketsPerPriority() {
        return ticketsPerPriority;
    }

    public void setTicketsPerPriority(EnumMap<Priority, Long> ticketsPerPriority) {
        this.ticketsPerPriority = ticketsPerPriority;
    }
}
