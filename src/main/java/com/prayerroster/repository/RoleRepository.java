package com.prayerroster.repository;

import com.prayerroster.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(String name);

    /** Fetches the role with its permissions in one query, avoiding an N+1 on the lazy collection. */
    @Query("select distinct r from Role r left join fetch r.permissions where r.name = :name")
    Optional<Role> findByNameWithPermissions(String name);

    /** Fetches every role with its permissions in one query, avoiding an N+1 on the lazy collection. */
    @Query("select distinct r from Role r left join fetch r.permissions order by r.name")
    List<Role> findAllWithPermissions();
}
