package com.aicompany.backend.daily.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.daily.model.DailyItem;
import com.aicompany.backend.daily.service.DailyService;
import com.aicompany.backend.task.model.Task;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Daily Work (directive §18). */
@RestController
public class DailyController {

    public record DailyRequest(LocalDate day, @Size(max = 255) String title, @Size(max = 2000) String notes,
                               DailyItem.Status status, DailyItem.Priority priority, LocalTime dueTime, Long taskId,
                               Integer position) {
        DailyService.Draft draft() {
            return new DailyService.Draft(day, title, notes, status, priority, dueTime, taskId, position);
        }
    }

    /** A referenced task travels with its own title, code, status and project -- read, not copied. */
    public record TaskRef(Long id, String code, String title, String status, Long projectId, String projectName) {
    }

    public record DailyResponse(Long id, LocalDate day, String title, String notes, DailyItem.Status status,
                                DailyItem.Priority priority, LocalTime dueTime, int position, TaskRef task,
                                long version) {
        public static DailyResponse from(DailyItem item) {
            Task t = item.getTask();
            TaskRef ref = t == null ? null : new TaskRef(t.getId(), t.getCode(), t.getTitle(), t.getStatus().name(),
                    t.getProjectId(), t.getProject() == null ? null : t.getProject().getName());
            return new DailyResponse(item.getId(), item.getDay(), item.getTitle() != null ? item.getTitle()
                    : t == null ? null : t.getTitle(), item.getNotes(), item.getStatus(), item.getPriority(),
                    item.getDueTime(), item.getPosition(), ref, item.getVersion());
        }
    }

    private final DailyService service;

    public DailyController(DailyService service) {
        this.service = service;
    }

    @GetMapping("/api/daily")
    public List<DailyResponse> between(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.between(from, to).stream().map(DailyResponse::from).toList();
    }

    @PostMapping("/api/daily")
    public ResponseEntity<DailyResponse> create(@Valid @RequestBody DailyRequest request) {
        DailyItem item = service.create(request.draft());
        return ResponseEntity.created(URI.create("/api/daily/" + item.getId())).eTag(ETags.of(item.getVersion()))
                .body(DailyResponse.from(item));
    }

    @PutMapping("/api/daily/{id}")
    public ResponseEntity<DailyResponse> update(@PathVariable Long id,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                @Valid @RequestBody DailyRequest request) {
        DailyItem item = service.update(id, request.draft(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(item.getVersion())).body(DailyResponse.from(item));
    }

    @DeleteMapping("/api/daily/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        service.remove(id, Precondition.fromHeader(ifMatch));
        return ResponseEntity.noContent().build();
    }

    /** What was not done on {@code from} moves to {@code to}. */
    @PostMapping("/api/daily/carry-over")
    public List<DailyResponse> carryOver(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.carryOver(from, to).stream().map(DailyResponse::from).toList();
    }
}
