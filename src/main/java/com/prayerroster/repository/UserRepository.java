package com.prayerroster.repository;

import com.prayerroster.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Fetches the user with its role and the role's permissions in one query - this is the path
     * called on every authenticated request (authority resolution), so it must never N+1.
     */
    @Query("select distinct u from User u join fetch u.role r left join fetch r.permissions where u.id = :id")
    Optional<User> findByIdWithRoleAndPermissions(String id);
}
