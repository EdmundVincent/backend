package com.ivis.component.exception;

/**
 * リソースが見つからない例外
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(String.format("%s not found: %s", resourceType, identifier));
    }
}
