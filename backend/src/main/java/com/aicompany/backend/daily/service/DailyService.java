package com.aicompany.backend.daily.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.daily.exception.DailyItemNotFoundException;
import com.aicompany.backend.daily.model.DailyItem;
import com.aicompany.backend.daily.repository.DailyItemRepository;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Daily Work (directive §18): the operator's own layer of organisation above
 * the projects. Items are personal, or references to project tasks -- which is
 * what lets "today" aggregate work from different projects without copying it.
 */
@Service
@Transactional
public class DailyService {

    public record Draft(LocalDate day, String title, String notes, DailyItem.Status status,
                        DailyItem.Priority priority, LocalTime dueTime, Long taskId, Integer position) {
    }

    private final DailyItemRepository items;
    private final TaskRepository tasks;

    public DailyService(DailyItemRepository items, TaskRepository tasks) {
        this.items = items;
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public List<DailyItem> between(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new RequestValidationException("to", "must not be before from");
        }
        if (from.plusDays(62).isBefore(to)) {
            throw new RequestValidationException("to", "at most two months at a time");
        }
        return items.findBetween(from, to);
    }

    public DailyItem create(Draft draft) {
        if (draft.day() == null) {
            throw new RequestValidationException("day", "is required");
        }
        Task task = null;
        if (draft.taskId() != null) {
            task = tasks.findById(draft.taskId()).orElseThrow(() -> new TaskNotFoundException(draft.taskId()));
        } else if (draft.title() == null || draft.title().isBlank()) {
            throw new RequestValidationException("title", "a personal item needs a title; or reference a task");
        }
        int position = draft.position() != null ? draft.position() : items.countByDay(draft.day());
        return hydrated(items.saveAndFlush(new DailyItem(draft.day(), draft.title(), draft.notes(), draft.priority(),
                draft.dueTime(), task, position)));
    }

    public DailyItem update(Long id, Draft draft, Precondition precondition) {
        DailyItem item = items.findByIdForUpdate(id).orElseThrow(() -> new DailyItemNotFoundException(id));
        precondition.requireSatisfiedBy(item.getVersion());
        if (item.getTask() == null && (draft.title() == null || draft.title().isBlank())) {
            throw new RequestValidationException("title", "a personal item needs a title");
        }
        item.update(draft.day() == null ? item.getDay() : draft.day(), draft.title(), draft.notes(),
                draft.status() == null ? item.getStatus() : draft.status(),
                draft.priority() == null ? item.getPriority() : draft.priority(), draft.dueTime(),
                draft.position() == null ? item.getPosition() : draft.position());
        items.flush();
        return hydrated(item);
    }

    /** The operator removes a line from a day. A referenced task is untouched. */
    public void remove(Long id, Precondition precondition) {
        DailyItem item = items.findByIdForUpdate(id).orElseThrow(() -> new DailyItemNotFoundException(id));
        precondition.requireSatisfiedBy(item.getVersion());
        items.delete(item);
    }

    /** Moves what was not done on {@code from} to {@code to}: "domani" in one click. */
    public List<DailyItem> carryOver(LocalDate from, LocalDate to) {
        List<DailyItem> open = items.findBetween(from, from).stream()
                .filter(item -> item.getStatus() != DailyItem.Status.DONE)
                .toList();
        int base = items.countByDay(to);
        for (int i = 0; i < open.size(); i++) {
            DailyItem item = open.get(i);
            item.update(to, item.getTitle(), item.getNotes(), item.getStatus(), item.getPriority(), item.getDueTime(),
                    base + i);
        }
        items.flush();
        open.forEach(DailyService::hydrated);
        return open;
    }

    /** The referenced task and its project, loaded while the transaction is open (open-in-view is off). */
    private static DailyItem hydrated(DailyItem item) {
        if (item.getTask() != null) {
            Hibernate.initialize(item.getTask());
            if (item.getTask().getProject() != null) {
                Hibernate.initialize(item.getTask().getProject());
            }
        }
        return item;
    }
}
