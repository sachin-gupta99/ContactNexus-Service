package com.sachingupta.contactnexus.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface TagRepository extends JpaRepository<Tag, Long> {

    @Query("select t from Tag t where t.ownerId = :ownerId and lower(t.name) in :lowerNames")
    List<Tag> findByOwnerAndLowerNames(Long ownerId, Collection<String> lowerNames);

    /** Name and how many of the owner's contacts carry it, alphabetical. */
    @Query("""
            select t.name, count(c.id) from Contact c join c.tags t
            where c.ownerId = :ownerId
            group by t.id, t.name
            order by lower(t.name)
            """)
    List<Object[]> countsForOwner(Long ownerId);

    long countByOwnerId(Long ownerId);

    /** Tags only exist while something uses them; called after edits that may orphan one. */
    @Modifying
    @Query("""
            delete from Tag t where t.ownerId = :ownerId
            and not exists (select 1 from Contact c join c.tags ct where ct.id = t.id)
            """)
    int deleteUnused(Long ownerId);

    /** The contact_tags rows go with it (ON DELETE CASCADE). */
    @Modifying
    @Query("delete from Tag t where t.ownerId = :ownerId and lower(t.name) = :lowerName")
    int deleteByOwnerAndLowerName(Long ownerId, String lowerName);

    @Modifying
    @Query("delete from Tag t where t.ownerId = :ownerId")
    int deleteByOwnerId(Long ownerId);
}
