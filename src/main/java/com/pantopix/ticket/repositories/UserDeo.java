package com.pantopix.ticket.repositories;

import com.pantopix.ticket.entities.User;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserDeo extends CrudRepository<User, Long> {

}
