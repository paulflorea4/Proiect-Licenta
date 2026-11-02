package com.gradingplatform.backend.controller;

import com.gradingplatform.backend.dto.SigninRequest;
import com.gradingplatform.backend.dto.SigninResponse;
import com.gradingplatform.backend.dto.SignupRequest;
import com.gradingplatform.backend.dto.UserResponse;
import com.gradingplatform.backend.security.AuthCookie;
import com.gradingplatform.backend.security.AuthenticatedUser;
import com.gradingplatform.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signup(@Valid @RequestBody SignupRequest request) {
        return UserResponse.from(authService.signup(request));
    }

    /** The signed-in user, from the token's subject. Needs a valid token (the chain's default rule). */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return UserResponse.from(authService.currentUser(principal.id()));
    }

    /** The token goes into the {@code httpOnly} cookie only (8.2b), never into the body. */
    @PostMapping("/signin")
    public ResponseEntity<SigninResponse> signin(@Valid @RequestBody SigninRequest request) {
        AuthService.SigninResult result = authService.signin(request);
        ResponseCookie cookie =
                AuthCookie.issue(result.token().value(), result.token().validFor());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new SigninResponse(result.token().validFor().toSeconds(), UserResponse.from(result.user())));
    }
}
