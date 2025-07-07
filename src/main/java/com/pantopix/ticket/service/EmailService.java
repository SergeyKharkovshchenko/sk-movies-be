package com.pantopix.ticket.service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    public void sendSimpleEmail(String[] senders, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("notify@pantopix.com");
        message.setTo(senders);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
