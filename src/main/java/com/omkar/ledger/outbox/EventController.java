package com.omkar.ledger.outbox;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /** Lets a merchant re-fetch an event it received by webhook, from the source of truth. */
    @GetMapping("/v1/events/{id}")
    @Operation(summary = "Get an event")
    public EventEnvelope get(@PathVariable("id") UUID id) {
        return eventService.get(id);
    }
}
