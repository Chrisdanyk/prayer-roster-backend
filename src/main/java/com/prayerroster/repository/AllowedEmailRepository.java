package com.prayerroster.repository;

import com.prayerroster.domain.AllowedEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllowedEmailRepository extends JpaRepository<AllowedEmail, Long> {
    /**
     * Spelled out rather than derived. Sprint 7 shipped a derived-query name that failed at real
     * repository initialisation because Spring Data split it on a reserved keyword, and
     * Mockito-mocked repositories never surface that class of bug.
     */
    @Query("select count(a) > 0 from AllowedEmail a where lower(a.email) = lower(:email)")
    boolean existsByEmailIgnoringCase(@Param("email") String email);
}
