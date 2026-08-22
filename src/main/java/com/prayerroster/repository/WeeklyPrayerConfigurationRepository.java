package com.prayerroster.repository;

import com.prayerroster.domain.WeeklyPrayerConfiguration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WeeklyPrayerConfigurationRepository extends JpaRepository<WeeklyPrayerConfiguration, Long> {
    @Query("select distinct c from WeeklyPrayerConfiguration c left join fetch c.days where c.effectiveTo is null")
    Optional<WeeklyPrayerConfiguration> findCurrent();

    @Query("select distinct c from WeeklyPrayerConfiguration c left join fetch c.days order by c.effectiveFrom desc")
    List<WeeklyPrayerConfiguration> findAllWithDaysOrderByEffectiveFromDesc();
}
