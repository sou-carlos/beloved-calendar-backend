package br.com.beloved.auth;

import br.com.beloved.user.AppUser;
import br.com.beloved.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static br.com.beloved.auth.AuthDtos.*;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;

    public AuthService(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma conta com este email.");
        }
        return UserResponse.from(users.saveAndFlush(
            new AppUser(request.name(), request.email(), passwords.encode(request.password()))));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String email) {
        return users.findByEmail(email).map(UserResponse::from)
            .orElseThrow(() -> new UsernameNotFoundException("Conta não encontrada."));
    }
}
