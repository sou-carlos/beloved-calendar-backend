package br.com.beloved.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import static br.com.beloved.auth.AuthDtos.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService users;
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contexts;

    public AuthController(AuthService users, AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionStrategy, SecurityContextRepository contexts) {
        this.users = users;
        this.authenticationManager = authenticationManager;
        this.sessionStrategy = sessionStrategy;
        this.contexts = contexts;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        users.register(body);
        return signIn(body.email(), body.password(), request, response);
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return signIn(body.email(), body.password(), request, response);
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return users.currentUser(authentication.getName());
    }

    private UserResponse signIn(String email, String password,
            HttpServletRequest request, HttpServletResponse response) {
        var authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        sessionStrategy.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return users.currentUser(authentication.getName());
    }
}
