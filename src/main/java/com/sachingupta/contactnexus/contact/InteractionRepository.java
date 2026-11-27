package com.sachingupta.contactnexus.contact;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InteractionRepository extends JpaRepository<Interaction, Long> {

    List<Interaction> findByContactIdOrderByOccurredAtDescIdDesc(Long contactId, Pageable pageable);

    Optional<Interaction> findByIdAndContactIdAndOwnerId(Long id, Long contactId, Long ownerId);

    @Query("select max(i.occurredAt) from Interaction i where i.contactId = :contactId")
    Optional<Instant> latestFor(Long contactId);

    /** Merging contacts: their timelines move to the one that is kept. */
    @Modifying
    @Query("update Interaction i set i.contactId = :keepId where i.ownerId = :ownerId and i.contactId in :fromIds")
    int reassign(Long ownerId, Collection<Long> fromIds, Long keepId);
}
