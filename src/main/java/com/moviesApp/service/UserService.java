package com.moviesApp.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.moviesApp.common.CommentWithMovieDto;

import com.moviesApp.entities.Movie;
import com.moviesApp.entities.User;
import com.moviesApp.entities.Comment;

import com.moviesApp.repositories.CommentRepo;
import com.moviesApp.repositories.UserRepository;

@Service
public class UserService {

    private UserRepository userRepository;

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

    public String findUsernameById(String userId) {
        return userRepository.findById(userId)
                .map(User::getUsername)
                .orElse("Unknown");
    }

}
