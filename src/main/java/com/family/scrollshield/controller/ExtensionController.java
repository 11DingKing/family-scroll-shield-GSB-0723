package com.family.scrollshield.controller;

import com.family.scrollshield.dto.request.ApproveExtensionRequest;
import com.family.scrollshield.dto.request.RequestExtensionRequest;
import com.family.scrollshield.dto.response.ExtensionResponse;
import com.family.scrollshield.service.ExtensionApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/extensions")
@RequiredArgsConstructor
public class ExtensionController {

    private final ExtensionApprovalService extensionApprovalService;

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.CREATED)
    public ExtensionResponse requestExtension(@Valid @RequestBody RequestExtensionRequest request) {
        return extensionApprovalService.requestExtension(request);
    }

    @PostMapping("/{approvalToken}/approve")
    public ExtensionResponse approveExtension(
            @PathVariable UUID approvalToken,
            @Valid @RequestBody ApproveExtensionRequest request) {
        return extensionApprovalService.approveExtension(approvalToken, request);
    }

    @GetMapping("/{approvalToken}")
    public ExtensionResponse getApproval(@PathVariable UUID approvalToken) {
        return extensionApprovalService.getApproval(approvalToken);
    }
}
