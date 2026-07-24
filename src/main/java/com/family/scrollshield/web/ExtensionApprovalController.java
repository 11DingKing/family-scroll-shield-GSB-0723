package com.family.scrollshield.web;

import com.family.scrollshield.dto.ExtensionRequest;
import com.family.scrollshield.dto.ExtensionResponse;
import com.family.scrollshield.service.ExtensionApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/extensions")
@RequiredArgsConstructor
public class ExtensionApprovalController {

    private final ExtensionApprovalService extensionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExtensionResponse grant(@Valid @RequestBody ExtensionRequest req) {
        return extensionService.grant(req);
    }
}
