package com.prayerroster.repository;

import com.prayerroster.domain.ReminderConfiguration;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReminderConfigurationRepository extends JpaRepository<ReminderConfiguration, Long> {
    List<ReminderConfiguration> findByActiveTrue();

    // Not a derived query: Spring Data parses "DaysBefore" as property "days" + the reserved
    // "Before" (date-comparison) keyword and fails to find a "days" property - "daysBefore" as a
    // whole is unfortunately not distinguishable from that keyword by the derivation parser.
    @Query("select count(r) > 0 from ReminderConfiguration r where r.daysBefore = :daysBefore")
    boolean existsByDaysBefore(@Param("daysBefore") Integer daysBefore);
}
