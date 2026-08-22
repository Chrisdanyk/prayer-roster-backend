package com.prayerroster.repository;

import com.prayerroster.domain.EmailStatus;
import com.prayerroster.domain.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Query(
        value = "select n from Notification n join fetch n.recipient where n.recipient.id = :recipientId order by n.createdDate desc",
        countQuery = "select count(n) from Notification n where n.recipient.id = :recipientId"
    )
    Page<Notification> findByRecipientId(@Param("recipientId") String recipientId, Pageable pageable);

    @Query("select n from Notification n join fetch n.recipient where n.id = :id and n.recipient.id = :recipientId")
    Optional<Notification> findByIdAndRecipientId(@Param("id") Long id, @Param("recipientId") String recipientId);

    @Query("select n from Notification n join fetch n.recipient where n.id = :id")
    Optional<Notification> findByIdWithRecipient(@Param("id") Long id);

    @Query("select n from Notification n join fetch n.recipient where n.emailStatus = :status and n.retryCount < :maxRetries")
    List<Notification> findRetryableByEmailStatus(@Param("status") EmailStatus status, @Param("maxRetries") int maxRetries);
}
