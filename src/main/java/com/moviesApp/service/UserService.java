package com.moviesApp.service;

import org.springframework.stereotype.Service;
import com.moviesApp.entities.User;
import com.moviesApp.repositories.UserRepository;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createNewUser(User user) {
        User newUser = new User();
        newUser.setId(user.getId()); 
        newUser.setUsername(user.getUsername());
        return userRepository.save(newUser); 
    }

    public Iterable<User> getAllUsers() {
        return userRepository.findAll();
    }
}
