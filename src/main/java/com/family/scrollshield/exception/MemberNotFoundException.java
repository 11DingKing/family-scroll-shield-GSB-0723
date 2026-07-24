package com.family.scrollshield.exception;

import java.util.UUID;

public class MemberNotFoundException extends ScrollShieldException {

    public MemberNotFoundException(UUID memberId) {
        super("Member not found: " + memberId, "MEMBER_NOT_FOUND");
    }
}
