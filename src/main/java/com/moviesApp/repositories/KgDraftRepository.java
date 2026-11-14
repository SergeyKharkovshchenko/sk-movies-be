package com.moviesApp.repositories;

import com.moviesApp.entities.KgDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface KgDraftRepository extends JpaRepository<KgDraft, Long> {

    Optional<KgDraft> findByLabelAndStage(String label, String stage);

    List<KgDraft> findByLabel(String label);

    @Modifying
    @Transactional
    void deleteByLabel(String label);
}
