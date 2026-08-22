package com.prayerroster.repository;

import com.prayerroster.domain.Roster;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RosterRepository extends JpaRepository<Roster, Long> {}
