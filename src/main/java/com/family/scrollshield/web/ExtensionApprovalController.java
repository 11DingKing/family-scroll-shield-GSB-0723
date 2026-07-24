package com.family.scrollshield.web;

import com.family.scrollshield.domain.ExtensionApproval;
import com.family.scrollshield.dto.ExtensionRequest;
import com.family.scrollshield.dto.ExtensionResponse;
import com.family.scrollshield.service.ExtensionApprovalService;
import com.family.scrollshield.service.ViewingPlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/extensions")
public class ExtensionApprovalController {

    private final ExtensionApprovalService extensionService;
    private final ViewingPlanService planService;

    public ExtensionApprovalController(ExtensionApprovalService extensionService,
                                       ViewingPlanService planService) {
        this.extensionService = extensionService;
        this.planService = planService;
    }

    @PostMapping("/approve")
    public ResponseEntity<ExtensionResponse> approve(@Valid @RequestBody ExtensionRequest request) {
        ExtensionApproval approval = extensionService.approve(
                request.memberExternalId(), request.approver(), request.reason());
        int remaining = planService.remainingByPlanId(approval.getPlanId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ExtensionResponse.of(approval, remaining));
    }
}
