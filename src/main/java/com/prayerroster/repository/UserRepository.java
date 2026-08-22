package com.prayerroster.repository;

import com.prayerroster.domain.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * The Timefold value range for a solve: active users with at least one capability. Anyone with
     * neither {@code canModerate} nor {@code canPreach} can never fill any assignment, so excluding
     * them up front keeps the solver's search space to only genuinely eligible candidates.
     */
    @Query("select u from User u where u.active = true and (u.canModerate = true or u.canPreach = true)")
    List<User> findAllEligibleActive();

    /**
     * Fetches the user with its role and the role's permissions in one query - this is the path
     * called on every authenticated request (authority resolution), so it must never N+1.
     */
    @Query("select distinct u from User u join fetch u.role r left join fetch r.permissions where u.id = :id")
    Optional<User> findByIdWithRoleAndPermissions(String id);

    /**
     * Fetches a page of users with their role in the same query - role is a @ManyToOne, so this
     * join fetch is safe to combine with Pageable (no in-memory pagination, unlike a collection fetch).
     */
    @Query(value = "select u from User u join fetch u.role", countQuery = "select count(u) from User u")
    Page<User> findAllWithRole(Pageable pageable);

    @Query("select u from User u join fetch u.role where u.id = :id")
    Optional<User> findByIdWithRole(String id);
}
