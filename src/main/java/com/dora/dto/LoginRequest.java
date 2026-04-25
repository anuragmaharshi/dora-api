package com.dora.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Login credential payload. The LLD spec calls the field "username" in some places
 * and "email" in others; we use email throughout because {@code app_user.email} is
 * the unique login identifier (LLD-02 §4 table header says "email, password").
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}
