package com.family.scrollshield;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
public class TestWebhookController {

    private final CopyOnWriteArrayList<Map<String, Object>> receivedEvents = new CopyOnWriteArrayList<>();

    @PostMapping("/test-webhook")
    public void receiveEvent(@RequestBody Map<String, Object> event) {
        receivedEvents.add(event);
    }

    public List<Map<String, Object>> getReceivedEvents() {
        return new ArrayList<>(receivedEvents);
    }

    public void clear() {
        receivedEvents.clear();
    }
}
