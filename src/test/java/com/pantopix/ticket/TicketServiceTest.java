package com.pantopix.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;

import com.pantopix.ticket.common.CategoryDto;
import com.pantopix.ticket.common.TicketDto;
import com.pantopix.ticket.entities.Ticket;

import com.pantopix.ticket.service.TicketService;

import com.pantopix.ticket.model.CategoryStatus;
import com.pantopix.ticket.model.TicketPriority;
import com.pantopix.ticket.model.TicketStatus;

@SpringBootTest
class TicketServiceTest {

    private TicketService ticketService;

    @Test
    void testCreateNewTicket() {
        TicketDto ticketDto = new TicketDto();
        ticketDto.setProblem("123");
        ticketDto.setDesc("123");
        ticketDto.setCreatedBy("123");
        ticketDto.setAssignedTo("123");
        ticketDto.setPriority(TicketPriority.MEDIUM);
        ticketDto.setStatus(TicketStatus.OPEN);

        CategoryDto category = new CategoryDto();
        category.setCategoryId(123L);
        category.setCategoryName(CategoryStatus.EPIC);
        Set<CategoryDto> categories = new HashSet<>();
        categories.add(category);
        ticketDto.setCategories(categories);

        Ticket result = ticketService.createNewTicket(ticketDto);

        String input = ticketDto.getProblem();
        String output = result.getProblem();

        System.out.println("Input: " + input + ", Output: " + output);
        assertEquals(input, output);
    }
}
