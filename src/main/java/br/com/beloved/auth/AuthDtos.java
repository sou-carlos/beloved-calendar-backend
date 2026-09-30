package br.com.beloved.auth;

import br.com.beloved.user.AppUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public static String normalizeEmail(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    public record RegisterRequest(
        @NotBlank(message = "Informe seu nome.")
        @Size(min = 2, max = 80, message = "O nome deve ter entre 2 e 80 caracteres.") String name,
        @NotBlank(message = "Informe seu email.")
        @Email(message = "Informe um email válido.")
        @Size(max = 254, message = "O email é muito longo.") String email,
        @NotBlank(message = "Informe sua senha.")
        @Size(min = 8, max = 128, message = "A senha deve ter entre 8 e 128 caracteres.") String password
    ) {
        public RegisterRequest {
            name = name == null ? null : name.strip();
            email = normalizeEmail(email);
        }
    }

    public record LoginRequest(
        @NotBlank(message = "Informe seu email.")
        @Email(message = "Informe um email válido.")
        @Size(max = 254) String email,
        @NotBlank(message = "Informe sua senha.")
        @Size(max = 128) String password
    ) {
        public LoginRequest { email = normalizeEmail(email); }
    }

    public record UserResponse(UUID id, String name, String email, Instant createdAt) {
        public static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
        }
    }

    public record CsrfResponse(String token, String headerName) {}
}
