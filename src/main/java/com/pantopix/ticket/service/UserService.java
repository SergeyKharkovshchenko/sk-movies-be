
package com.pantopix.ticket.service;

import com.pantopix.ticket.entities.User;
import com.pantopix.ticket.repositories.UserDeo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    @Autowired
    private UserDeo userDeo;

    public User createNewUser(User user) {
        User newUser = new User();
        // newUser.setId(user.getId());
        newUser.setUsername(user.getUsername());
        return userDeo.save(newUser);
    }

    public Iterable<User> getAllUsers() {
        return userDeo.findAll();
    }

}
