package com.family.scrollshield.exception;

public class ExtensionDeniedException extends ScrollShieldException {

    public ExtensionDeniedException(String message) {
        super(message, "EXTENSION_DENIED");
    }
}
