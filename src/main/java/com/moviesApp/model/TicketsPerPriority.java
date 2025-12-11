package com.moviesApp.model;

import java.util.EnumMap;

public class TicketsPerPriority {

    public enum Priority {
        HIGH, MEDIUM, LOW
    }

    private EnumMap<Priority, Long> ticketsPerPriority;

    public EnumMap<Priority, Long> getTicketsPerPriority() {
        return ticketsPerPriority;
    }

    public void setTicketsPerPriority(EnumMap<Priority, Long> ticketsPerPriority) {
        this.ticketsPerPriority = ticketsPerPriority;

    }
}