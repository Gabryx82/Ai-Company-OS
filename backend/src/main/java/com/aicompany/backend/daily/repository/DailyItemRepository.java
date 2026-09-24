package com.aicompany.backend.daily.repository;

import com.aicompany.backend.daily.model.DailyItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyItemRepository extends JpaRepository<DailyItem, Long> {

    @Query("select d from DailyItem d left join fetch d.task t left join fetch t.project "
            + "where d.day between :from and :to order by d.day, d.position, d.id")
    List<DailyItem> findBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(d) from DailyItem d where d.day = :day")
    int countByDay(@Param("day") LocalDate day);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DailyItem d where d.id = :id")
    Optional<DailyItem> findByIdForUpdate(@Param("id") Long id);
}
