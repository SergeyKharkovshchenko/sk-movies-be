package com.moviesApp.controllers;

import com.moviesApp.entities.Relation;
import com.moviesApp.service.RelationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class RelationController {

    @Autowired
    private RelationService relationService;

    @GetMapping("/relations")
    public ResponseEntity<List<Relation>> getParentChildRelations() {
        List<Relation> relations = relationService.getParentChildRelations();
        return ResponseEntity.ok(relations);
    }

}
