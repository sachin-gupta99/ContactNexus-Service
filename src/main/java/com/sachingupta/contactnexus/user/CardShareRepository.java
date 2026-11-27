package com.sachingupta.contactnexus.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardShareRepository extends JpaRepository<CardShare, Long> {

    Optional<CardShare> findByToken(String token);
}
