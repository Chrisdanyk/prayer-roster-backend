package com.prayerroster.repository;

import com.prayerroster.domain.PrayerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrayerAssignmentRepository extends JpaRepository<PrayerAssignment, Long> {}
